package works.repka.rysclaims.server;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.piston.PistonStructureResolver;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.common.util.TriState;
import net.neoforged.neoforge.event.entity.EntityMobGriefingEvent;
import net.neoforged.neoforge.event.entity.ProjectileImpactEvent;
import net.neoforged.neoforge.event.entity.living.LivingDestroyBlockEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.player.AttackEntityEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.ExplosionEvent;
import net.neoforged.neoforge.event.level.PistonEvent;
import org.jetbrains.annotations.Nullable;
import works.repka.rysclaims.RYSClaims;
import works.repka.rysclaims.data.Chunks;
import works.repka.rysclaims.data.Rights;
import works.repka.rysclaims.data.Settings;
import works.repka.rysclaims.data.TeamLand;

/**
 * Защита событиями NeoForge (план, раздел 11, строки 1–6, 8–18, 29). Одна проверка {@link ClaimService#can} для игроков и
 * {@link ClaimService#canMachine} для фейковых игроков: место фейкового игрока не доверяем (R0: спавн, под миром, чужая
 * машина), машина судится профилем, списком имён команды и переключателем «Машины снаружи».
 *
 * <p>Каждый обработчик сначала выходит дёшево: сервер земли не запущен, в измерении нет земли (и свободная земля открыта).
 * Перехваты жидкости, огня, молнии (строки 19, 20) и Create, Sable идут следующими частями.
 */
public final class Protection {
    public static final TagKey<Block> USE = TagKey.create(Registries.BLOCK, ResourceLocation.fromNamespaceAndPath(RYSClaims.MODID, "use"));
    public static final TagKey<Block> FREE = TagKey.create(Registries.BLOCK, ResourceLocation.fromNamespaceAndPath(RYSClaims.MODID, "free"));
    public static final TagKey<Item> BUILD_ITEMS = TagKey.create(Registries.ITEM, ResourceLocation.fromNamespaceAndPath(RYSClaims.MODID, "build_items"));

    /** Когда игроку последний раз показали отказ: строка над хотбаром не чаще раза в секунду. */
    private static final Map<UUID, Long> LAST_DENY = new HashMap<>();

    private Protection() {}

    public static void register(IEventBus bus) {
        bus.addListener(EventPriority.HIGH, BlockEvent.BreakEvent.class, Protection::onBreak);
        bus.addListener(EventPriority.HIGH, BlockEvent.EntityPlaceEvent.class, Protection::onPlace);
        bus.addListener(EventPriority.HIGH, PlayerInteractEvent.LeftClickBlock.class, Protection::onLeftClick);
        bus.addListener(EventPriority.HIGH, PlayerInteractEvent.RightClickBlock.class, Protection::onRightClickBlock);
        bus.addListener(EventPriority.HIGH, PlayerInteractEvent.RightClickItem.class, Protection::onRightClickItem);
        bus.addListener(EventPriority.HIGH, BlockEvent.BlockToolModificationEvent.class, Protection::onTool);
        bus.addListener(EventPriority.HIGH, PlayerInteractEvent.EntityInteract.class, e -> {
            if (!entityAllowed(e.getEntity(), e.getTarget())) e.setCanceled(true);
        });
        bus.addListener(EventPriority.HIGH, PlayerInteractEvent.EntityInteractSpecific.class, e -> {
            if (!entityAllowed(e.getEntity(), e.getTarget())) e.setCanceled(true);
        });
        bus.addListener(EventPriority.HIGH, AttackEntityEvent.class, e -> {
            if (!(e.getTarget() instanceof Player) && !entityAllowed(e.getEntity(), e.getTarget())) e.setCanceled(true);
        });
        bus.addListener(EventPriority.HIGH, LivingIncomingDamageEvent.class, Protection::onDamage);
        bus.addListener(EventPriority.HIGH, BlockEvent.FarmlandTrampleEvent.class, Protection::onTrample);
        bus.addListener(EventPriority.HIGH, ProjectileImpactEvent.class, Protection::onProjectile);
        bus.addListener(EventPriority.HIGH, ExplosionEvent.Detonate.class, Protection::onDetonate);
        bus.addListener(EventPriority.LOW, EntityMobGriefingEvent.class, Protection::onMobGrief);
        bus.addListener(EventPriority.HIGH, LivingDestroyBlockEvent.class, e -> {
            if (!mobMayGrief(e.getEntity(), e.getEntity().level(), e.getPos())) e.setCanceled(true);
        });
        bus.addListener(EventPriority.HIGH, PistonEvent.Pre.class, Protection::onPiston);
    }

    // ---- общая проверка ----

    private static @Nullable ClaimService service(Level level) {
        if (!(level instanceof ServerLevel) || !ClaimsServer.running()) return null;
        return ClaimsServer.service();
    }

    /** Есть ли что проверять в этом измерении: земля или закрытая свободная земля. */
    private static boolean relevant(ClaimService s, String dim) {
        return s.data().anyIn(dim) || s.settings().wildernessClosed(dim);
    }

    /** Может ли игрок (или фейковый игрок) сделать действие в точке. */
    public static boolean allowed(@Nullable Player player, Level level, BlockPos pos, Rights.Action action) {
        if (player == null) return true;
        ClaimService s = service(level);
        if (s == null) return true;
        String dim = Loader.id(level.dimension());
        if (!relevant(s, dim)) return true;
        long chunk = Chunks.pack(pos.getX() >> 4, pos.getZ() >> 4);
        if (player instanceof FakePlayer fake) return machine(s, fake, dim, chunk, action);
        return s.can(player.getUUID(), dim, chunk, action);
    }

    /** Фейковый игрок: список имён команды, затем профиль и «Машины снаружи». Место не доверяем. */
    static boolean machine(ClaimService s, FakePlayer fake, String dim, long chunk, Rights.Action action) {
        UUID owner = s.data().owner(dim, chunk);
        if (owner == null) return !s.settings().wildernessClosed(dim) || s.can(fake.getUUID(), dim, chunk, action);
        TeamLand land = s.data().land(owner);
        if (land != null && land.machineNames.contains(fake.getGameProfile().getName())) return true;
        return s.canMachine(dim, Long.MIN_VALUE, fake.getUUID(), chunk, action);
    }

    private static void deny(@Nullable Player player, Rights.Action action) {
        if (!(player instanceof ServerPlayer sp) || player instanceof FakePlayer) return;
        long now = System.currentTimeMillis();
        Long last = LAST_DENY.get(sp.getUUID());
        if (last != null && now - last < 1000) return;
        LAST_DENY.put(sp.getUUID(), now);
        Component text = switch (action) {
            case BUILD -> Msg.t("deny.build", "You cannot build here");
            case OPEN -> Msg.t("deny.open", "You cannot open this here");
            case USE -> Msg.t("deny.use", "You cannot use this here");
            case ENTITY -> Msg.t("deny.entity", "You cannot touch this here");
        };
        sp.displayClientMessage(text, true);
    }

    private static boolean check(@Nullable Player player, Level level, BlockPos pos, Rights.Action action) {
        if (allowed(player, level, pos, action)) return true;
        deny(player, action);
        return false;
    }

    // ---- блоки ----

    private static void onBreak(BlockEvent.BreakEvent e) {
        if (e.getLevel() instanceof Level level && !check(e.getPlayer(), level, e.getPos(), Rights.Action.BUILD)) e.setCanceled(true);
    }

    private static void onPlace(BlockEvent.EntityPlaceEvent e) {
        if (!(e.getEntity() instanceof Player p) || !(e.getLevel() instanceof Level level)) return;
        if (e instanceof BlockEvent.EntityMultiPlaceEvent multi) {
            for (var snap : multi.getReplacedBlockSnapshots()) {
                if (!check(p, level, snap.getPos(), Rights.Action.BUILD)) {
                    e.setCanceled(true);
                    return;
                }
            }
            return;
        }
        if (!check(p, level, e.getPos(), Rights.Action.BUILD)) e.setCanceled(true);
    }

    private static void onLeftClick(PlayerInteractEvent.LeftClickBlock e) {
        if (!check(e.getEntity(), e.getLevel(), e.getPos(), Rights.Action.BUILD)) e.setCanceled(true);
    }

    /** Правый клик по блоку: по умолчанию «Открывать»; тег {@code use} это «Двери»; тег {@code free} всегда; предмет-блок ещё «Строить». */
    private static void onRightClickBlock(PlayerInteractEvent.RightClickBlock e) {
        Player p = e.getEntity();
        Level level = e.getLevel();
        BlockPos pos = e.getPos();
        BlockState state = level.getBlockState(pos);
        if (!state.is(FREE)) {
            Rights.Action a = state.is(USE) ? Rights.Action.USE : Rights.Action.OPEN;
            if (!check(p, level, pos, a)) {
                // предмет в руке тоже не применяется: и блок, и предмет гасятся
                e.setCanceled(true);
                return;
            }
        }
        ItemStack stack = e.getItemStack();
        if (buildsWith(stack)) {
            BlockPos next = pos.relative(e.getFace() == null ? e.getHitVec().getDirection() : e.getFace());
            if (!check(p, level, pos, Rights.Action.BUILD) || !check(p, level, next, Rights.Action.BUILD)) {
                e.setUseItem(TriState.FALSE);
                if (state.is(FREE)) return;
                e.setCanceled(true);
            }
        }
    }

    private static boolean buildsWith(ItemStack stack) {
        return !stack.isEmpty() && (stack.getItem() instanceof BlockItem || stack.getItem() instanceof BucketItem || stack.is(BUILD_ITEMS));
    }

    /** Ведро, лодка и прочее, что ставится из {@code use}: событие не знает цели, повторяем луч и проверяем обе позиции. */
    private static void onRightClickItem(PlayerInteractEvent.RightClickItem e) {
        ItemStack stack = e.getItemStack();
        if (!(stack.getItem() instanceof BucketItem) && !stack.is(BUILD_ITEMS)) return;
        Player p = e.getEntity();
        HitResult hit = p.pick(p.blockInteractionRange(), 0, true);
        if (!(hit instanceof BlockHitResult bh) || hit.getType() != HitResult.Type.BLOCK) return;
        BlockPos pos = bh.getBlockPos();
        if (!check(p, e.getLevel(), pos, Rights.Action.BUILD) || !check(p, e.getLevel(), pos.relative(bh.getDirection()), Rights.Action.BUILD)) {
            e.setCanceled(true);
        }
    }

    private static void onTool(BlockEvent.BlockToolModificationEvent e) {
        if (e.isSimulated() || !(e.getLevel() instanceof Level level)) return;
        if (!check(e.getPlayer(), level, e.getPos(), Rights.Action.BUILD)) e.setCanceled(true);
    }

    private static void onTrample(BlockEvent.FarmlandTrampleEvent e) {
        if (!(e.getLevel() instanceof Level level)) return;
        if (e.getEntity() instanceof Player p) {
            if (!check(p, level, e.getPos(), Rights.Action.BUILD)) e.setCanceled(true);
        } else if (!mobMayGrief(e.getEntity(), level, e.getPos())) {
            e.setCanceled(true);
        }
    }

    // ---- сущности ----

    /** Сущность защищена правом «Животные, рамки, стойки», кроме монстров и мобов, которые целятся в этого игрока. */
    static boolean entityAllowed(Player player, Entity target) {
        if (target instanceof Player) return true;
        if (target instanceof Enemy) return true;
        if (target instanceof Mob mob && mob.getTarget() == player) return true;
        // свой питомец: можно всегда
        if (target instanceof OwnableEntity own && player.getUUID().equals(own.getOwnerUUID())) return true;
        return check(player, target.level(), target.blockPosition(), Rights.Action.ENTITY);
    }

    private static void onDamage(LivingIncomingDamageEvent e) {
        LivingEntity victim = e.getEntity();
        Entity src = e.getSource().getEntity();
        if (!(src instanceof Player attacker)) return;
        ClaimService s = service(victim.level());
        if (s == null) return;
        String dim = Loader.id(victim.level().dimension());
        if (!s.data().anyIn(dim)) return;
        if (victim instanceof Player) {
            if (attacker != victim && !pvpAllowed(s, dim, attacker, victim)) e.setCanceled(true);
            return;
        }
        if (!entityAllowed(attacker, victim)) e.setCanceled(true);
    }

    /** Бой игроков на земле: настройка сервера; «решает команда» берёт переключатель земли под жертвой и под нападающим. */
    static boolean pvpAllowed(ClaimService s, String dim, Entity attacker, Entity victim) {
        Settings.PvpMode mode = s.settings().pvpMode;
        if (mode == Settings.PvpMode.ALWAYS) return true;
        for (Entity e : new Entity[] {attacker, victim}) {
            UUID owner = s.data().owner(dim, Chunks.pack(e.getBlockX() >> 4, e.getBlockZ() >> 4));
            if (owner == null) continue;
            if (mode == Settings.PvpMode.NEVER) return false;
            TeamLand land = s.data().land(owner);
            if (land != null && !land.flag(TeamLand.Flag.PVP)) return false;
        }
        return true;
    }

    private static void onProjectile(ProjectileImpactEvent e) {
        Projectile proj = e.getProjectile();
        if (!(proj.getOwner() instanceof Player owner)) return;
        if (e.getRayTraceResult() instanceof EntityHitResult hit) {
            Entity target = hit.getEntity();
            if (target instanceof Player victim) {
                ClaimService s = service(proj.level());
                if (s != null && victim != owner && !pvpAllowed(s, Loader.id(proj.level().dimension()), owner, victim)) e.setCanceled(true);
                return;
            }
            if (!entityAllowed(owner, target)) e.setCanceled(true);
        }
    }

    // ---- взрывы ----

    /**
     * Блоки и сущности взрыва остаются в списке, только если это можно: хозяин игрок судится его правом; хозяин моб
     * переключателем «Мобы портят блоки»; без хозяина можно, только если центр на земле той же команды и включён «Свои
     * взрывы ломают блоки». Свободная земля открыта.
     */
    private static void onDetonate(ExplosionEvent.Detonate e) {
        Level level = e.getLevel();
        ClaimService s = service(level);
        if (s == null) return;
        String dim = Loader.id(level.dimension());
        if (!s.data().anyIn(dim)) return;
        Explosion ex = e.getExplosion();
        LivingEntity indirect = ex.getIndirectSourceEntity();
        Entity direct = ex.getDirectSourceEntity();
        Player player = indirect instanceof Player p ? p : null;
        boolean mob = player == null && (indirect instanceof Mob || direct instanceof Mob);
        Entity mobEntity = indirect instanceof Mob ? indirect : direct;
        var c = ex.center();
        UUID centerOwner = s.data().owner(dim, Chunks.pack(((int) Math.floor(c.x)) >> 4, ((int) Math.floor(c.z)) >> 4));
        Map<Long, Boolean> byChunk = new HashMap<>();
        Iterator<BlockPos> it = e.getAffectedBlocks().iterator();
        while (it.hasNext()) {
            BlockPos pos = it.next();
            long chunk = Chunks.pack(pos.getX() >> 4, pos.getZ() >> 4);
            boolean ok = byChunk.computeIfAbsent(chunk, ch -> explosionMay(s, dim, ch, player, mob, mobEntity, centerOwner, Rights.Action.BUILD));
            if (!ok) it.remove();
        }
        Map<Long, Boolean> byChunkEntity = new HashMap<>();
        Iterator<Entity> ents = e.getAffectedEntities().iterator();
        while (ents.hasNext()) {
            Entity target = ents.next();
            if (target instanceof Player || target instanceof Enemy) continue;
            long chunk = Chunks.pack(target.getBlockX() >> 4, target.getBlockZ() >> 4);
            boolean ok = byChunkEntity.computeIfAbsent(chunk, ch -> explosionMay(s, dim, ch, player, mob, mobEntity, centerOwner, Rights.Action.ENTITY));
            if (!ok) ents.remove();
        }
    }

    private static boolean explosionMay(ClaimService s, String dim, long chunk, @Nullable Player player, boolean mob,
                                        @Nullable Entity mobEntity, @Nullable UUID centerOwner, Rights.Action action) {
        UUID owner = s.data().owner(dim, chunk);
        if (owner == null) return true;
        TeamLand land = s.data().land(owner);
        if (player != null) {
            if (player instanceof FakePlayer fake) return machine(s, fake, dim, chunk, action);
            return s.can(player.getUUID(), dim, chunk, action);
        }
        if (mob) return land != null && land.flag(TeamLand.Flag.MOB_GRIEF);
        return owner.equals(centerOwner) && land != null && land.flag(TeamLand.Flag.OWN_EXPLOSIONS);
    }

    // ---- мобы ----

    /** Порчу блоков мобами судит земля; без земли решает игра. Мирные мобы (овцы, жители, големы) не трогаются. */
    static boolean mobMayGrief(@Nullable Entity entity, Level level, BlockPos pos) {
        if (entity == null || entity instanceof Player) return true;
        ClaimService s = service(level);
        if (s == null) return true;
        String dim = Loader.id(level.dimension());
        if (!s.data().anyIn(dim)) return true;
        UUID owner = s.data().owner(dim, Chunks.pack(pos.getX() >> 4, pos.getZ() >> 4));
        if (owner == null) return true;
        TeamLand land = s.data().land(owner);
        return land != null && land.flag(TeamLand.Flag.MOB_GRIEF);
    }

    private static void onMobGrief(EntityMobGriefingEvent e) {
        Entity entity = e.getEntity();
        if (!(entity instanceof Enemy) || !e.canGrief()) return;
        if (!mobMayGrief(entity, entity.level(), entity.blockPosition())) e.setCanGrief(false);
    }

    // ---- поршни ----

    /** Поршень: ни один двигаемый или ломаемый блок не уходит с земли другой команды и не приходит на неё. */
    private static void onPiston(PistonEvent.Pre e) {
        if (!(e.getLevel() instanceof Level level)) return;
        ClaimService s = service(level);
        if (s == null) return;
        String dim = Loader.id(level.dimension());
        if (!s.data().anyIn(dim)) return;
        BlockPos piston = e.getPos();
        UUID pistonOwner = s.data().owner(dim, Chunks.pack(piston.getX() >> 4, piston.getZ() >> 4));
        if (!nearOtherLand(s, dim, piston, pistonOwner)) return;
        PistonStructureResolver r = e.getStructureHelper();
        if (r == null || !r.resolve()) return;
        var dir = r.getPushDirection();
        for (BlockPos pos : r.getToPush()) {
            if (foreign(s, dim, pos, pistonOwner) || foreign(s, dim, pos.relative(dir), pistonOwner)) {
                e.setCanceled(true);
                return;
            }
        }
        for (BlockPos pos : r.getToDestroy()) {
            if (foreign(s, dim, pos, pistonOwner)) {
                e.setCanceled(true);
                return;
            }
        }
    }

    /** Поршень дотягивается не дальше 13 блоков: смотрим, есть ли рядом земля другой команды. */
    private static boolean nearOtherLand(ClaimService s, String dim, BlockPos pos, @Nullable UUID owner) {
        int cx0 = (pos.getX() - 13) >> 4, cx1 = (pos.getX() + 13) >> 4, cz0 = (pos.getZ() - 13) >> 4, cz1 = (pos.getZ() + 13) >> 4;
        for (int x = cx0; x <= cx1; x++) {
            for (int z = cz0; z <= cz1; z++) {
                UUID o = s.data().owner(dim, Chunks.pack(x, z));
                if (o != null && !o.equals(owner)) return true;
            }
        }
        return false;
    }

    private static boolean foreign(ClaimService s, String dim, BlockPos pos, @Nullable UUID pistonOwner) {
        UUID o = s.data().owner(dim, Chunks.pack(pos.getX() >> 4, pos.getZ() >> 4));
        return o != null && !o.equals(pistonOwner);
    }

    public static void clear() {
        LAST_DENY.clear();
    }
}

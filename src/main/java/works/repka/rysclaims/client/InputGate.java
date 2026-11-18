package works.repka.rysclaims.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.NeutralMob;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.decoration.HangingEntity;
import net.minecraft.world.entity.npc.AbstractVillager;
import net.minecraft.world.entity.vehicle.VehicleEntity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.neoforged.neoforge.client.event.InputEvent;
import org.jetbrains.annotations.Nullable;
import works.repka.rysclaims.compat.gravestone.Graves;
import works.repka.rysclaims.data.Rights;
import works.repka.rysclaims.net.ClaimsNet;
import works.repka.rysclaims.server.Protection;

/**
 * Гашение запрещённого нажатия на клиенте (план 9.2 и 11, «Что видит игрок при отказе», пункт 2). Событие
 * {@code InteractionKeyMappingTriggered} идёт до пакета серверу: в {@code startAttack}, каждый тик в {@code continueAttack}
 * и в {@code startUseItem}. Цель берётся из {@code Minecraft.hitResult}. При отмене рука не машет, блок не мигает, предмет не
 * дёргается, у прицела замок и причина, как от пакета {@code Denied}.
 *
 * <p>Гасится только то, в чём кэш уверен: чанк известен, права команды свежие ({@link LandCache#fresh}). Правила те же, что
 * у сервера ({@code Protection}): ЛКМ по блоку это «Строить»; ПКМ по блоку это «Открывать», тег {@code use} это «Двери»,
 * тег {@code free} всегда, предмет-блок ещё «Строить» в двух позициях. Из сущностей гасятся только те, что сервер защищает
 * без оговорок: рамки, стойки, картины, лодки, вагонетки, мирные животные, жители. Мобов с целью и чужих питомцев решает
 * сервер. Предмет в воздух (ведро без блока) тоже уходит серверу.
 */
public final class InputGate {
    private InputGate() {}

    /** Блок, который клиент гасит без замка, чтобы предмет в руке применился (позиция и тик игры). */
    private static @Nullable BlockPos skipPos;
    private static long skipTick;

    /**
     * Клиентский {@code RightClickBlock}: блок, отказ по которому решён в {@link #onInput} в пользу предмета, гасится с
     * результатом PASS. Тогда {@code Minecraft.startUseItem} идёт дальше и зовёт {@code useItem}: игрок ест, пьёт, натягивает
     * лук, поднимает щит, бросает жемчуг, глядя на чужой блок (перепроверка 18.11, пункт 5; так было в 0.0.11 и в FTB).
     */
    public static void onRightClickBlock(net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.RightClickBlock e) {
        Minecraft mc = Minecraft.getInstance();
        if (!e.getLevel().isClientSide || skipPos == null || mc.level == null || mc.level.getGameTime() != skipTick || !skipPos.equals(e.getPos())) return;
        e.setCanceled(true);
        e.setCancellationResult(net.minecraft.world.InteractionResult.PASS);
    }

    public static void onInput(InputEvent.InteractionKeyMappingTriggered e) {
        if (e.isPickBlock() || e.isCanceled()) return;
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        HitResult hit = mc.hitResult;
        if (p == null || mc.level == null || hit == null || hit.getType() == HitResult.Type.MISS || LandCache.hello() == null) return;
        String dim = mc.level.dimension().location().toString();
        Denial d = null;
        if (hit instanceof BlockHitResult bh) {
            if (e.isAttack()) {
                // своя могила Gravestone ломается на любой земле (сервер: Protection.onBreak)
                if (!Graves.own(p, mc.level, bh.getBlockPos())) d = check(dim, bh.getBlockPos(), Rights.Action.BUILD);
            } else {
                d = useOn(dim, mc, bh, p.getItemInHand(e.getHand()));
                // отказ по блоку, а в руке еда, лук, щит: блок гасится молча, предмет применяется
                if (d != null && d.block && (Protection.usesInAir(p.getMainHandItem()) || Protection.usesInAir(p.getOffhandItem()))) {
                    skipPos = bh.getBlockPos().immutable();
                    skipTick = mc.level.getGameTime();
                    return;
                }
            }
        } else if (hit instanceof EntityHitResult eh && guarded(p, eh.getEntity())) {
            d = check(dim, eh.getEntity().blockPosition(), Rights.Action.ENTITY);
        }
        if (d == null) return;
        e.setCanceled(true);
        e.setSwingHand(false);
        DenyFeedback.show(d.reason, d.owner);
    }

    /** {@code block}: отказ по самому блоку («Открывать», «Двери»), а не по постановке предмета. */
    private record Denial(String reason, String owner, boolean block) {
        Denial(String reason, String owner) {
            this(reason, owner, false);
        }

        Denial asBlock() {
            return new Denial(reason, owner, true);
        }
    }

    /** ПКМ по блоку: то же решение, что {@code Protection.onRightClickBlock}. */
    private static @Nullable Denial useOn(String dim, Minecraft mc, BlockHitResult bh, ItemStack stack) {
        BlockPos pos = bh.getBlockPos();
        BlockState state = mc.level.getBlockState(pos);
        boolean free = state.is(Protection.FREE);
        if (!free) {
            Denial d = check(dim, pos, state.is(Protection.USE) ? Rights.Action.USE : Rights.Action.OPEN);
            if (d != null) return d.asBlock();
        }
        // на «свободном» блоке (верстак) сервер гасит только предмет, а блок открывает: такое нажатие гасить нельзя
        if (free || !buildsWith(stack)) return null;
        Denial d = check(dim, pos, Rights.Action.BUILD);
        return d != null ? d : check(dim, pos.relative(bh.getDirection()), Rights.Action.BUILD);
    }

    private static boolean buildsWith(ItemStack stack) {
        return !stack.isEmpty() && (stack.getItem() instanceof BlockItem || stack.getItem() instanceof BucketItem || stack.is(Protection.BUILD_ITEMS));
    }

    /** Сущности, которые сервер защищает правом «Животные, рамки, стойки» без условий, видимых только ему. */
    private static boolean guarded(LocalPlayer p, Entity target) {
        if (target instanceof OwnableEntity own && p.getUUID().equals(own.getOwnerUUID())) return false;
        // монстров сервер не защищает (хоглин это Animal и Enemy); нападающего моба тоже, а цель моба клиент не знает,
        // поэтому злой моб (флаг агрессии синхронизирован) решается сервером (перепроверка 18.11, пункт 6)
        if (target instanceof net.minecraft.world.entity.monster.Enemy) return false;
        if (target instanceof net.minecraft.world.entity.Mob mob && (mob.isAggressive() || mob.getTarget() == p)) return false;
        if (target instanceof HangingEntity || target instanceof ArmorStand || target instanceof VehicleEntity) return true;
        if (target instanceof AbstractVillager) return true;
        return target instanceof Animal && !(target instanceof NeutralMob) && !(target instanceof OwnableEntity);
    }

    /** Отказ, если земля известна, права свежие и действия нет. Свободную землю и сомнения решает сервер. */
    private static @Nullable Denial check(String dim, BlockPos pos, Rights.Action action) {
        LandCache.ChunkState st = LandCache.at(dim, pos.getX() >> 4, pos.getZ() >> 4);
        if (st == null || !LandCache.fresh(st.team())) return null;
        int bit = 1 << action.ordinal();
        if (st.team().can(bit)) return null;
        ClaimsNet.Hello h = LandCache.hello();
        boolean own = h != null && st.team().id().equals(h.team());
        String reason = own && action == Rights.Action.BUILD ? "guest" : action.key();
        return new Denial(reason, st.team().name());
    }

    /** Для проверок: что решил бы гейт по чанку (null: пропустить на сервер). */
    public static @Nullable String decide(String dim, BlockPos pos, Rights.Action action) {
        Denial d = check(dim, pos, action);
        return d == null ? null : d.reason;
    }
}

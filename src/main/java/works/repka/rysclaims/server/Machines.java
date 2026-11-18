package works.repka.rysclaims.server;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.animal.Bee;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;
import works.repka.rysclaims.compat.create.ContraptionPlace;
import works.repka.rysclaims.compat.sable.SableLand;
import works.repka.rysclaims.data.Chunks;
import works.repka.rysclaims.data.Rights;

/**
 * Машины, которые ломают и ставят мимо событий (R0, раздел 2): их перехваты в {@code mixin/compat} зовут сюда. Место
 * машины это позиция её блок-сущности (ей можно верить, в отличие от позиции фейкового игрока), профиль это хозяин, если
 * машина его знает. Решает {@link ClaimService#canMachine}; отказ уходит в журнал хозяев цели с именем машины.
 *
 * <p>Дешёвый выход первым: цель в том же чанке, что машина, в измерении нет земли.
 */
public final class Machines {
    /** Дальность «рядом» для машин, чьё место известно точно: обе точки на земле одной команды, расстояние не важно. */
    public static final int FAR = Integer.MAX_VALUE;

    private Machines() {}

    private static @Nullable ClaimService service(Level level) {
        if (!(level instanceof ServerLevel) || !ClaimsServer.running()) return null;
        return ClaimsServer.service();
    }

    /** Блок-сущность машины ломает или ставит блок {@code target} в {@code level}. */
    public static boolean blockEntity(Object self, @Nullable Level level, BlockPos target) {
        if (!(self instanceof BlockEntity be) || level == null) return true;
        BlockPos from = be.getLevel() == level ? be.getBlockPos() : null;
        return at(level, from, null, target, name(be), FAR);
    }

    /** То же, уровень берётся у самой блок-сущности. */
    public static boolean blockEntity(Object self, @Nullable BlockPos target) {
        if (!(self instanceof BlockEntity be) || target == null) return true;
        return blockEntity(be, be.getLevel(), target);
    }

    /**
     * Постройка Create действует в {@code target} (бур, пила, каток, комбайн, плуг на ней): место это её якорь
     * ({@link ContraptionPlace}), хозяина у постройки нет. {@code movementContext} это {@code MovementContext} Create,
     * его поля берутся отражением.
     */
    public static boolean contraption(@Nullable Object movementContext, BlockPos target) {
        if (movementContext == null) return true;
        try {
            Field[] f = CONTEXT.get(movementContext.getClass());
            if (!(f[0].get(movementContext) instanceof Level level)) return true;
            Object contraption = f[1].get(movementContext);
            String name = f[2].get(movementContext) instanceof BlockState st ? BuiltInRegistries.BLOCK.getKey(st.getBlock()).toString() : "create:actor";
            return contraptionAt(level, contraption, target, name);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return true;
        }
    }

    /** Постройка {@code contraption} (её сборка, разборка, работник на ней) трогает блок {@code target}. */
    public static boolean contraptionAt(Level level, @Nullable Object contraption, BlockPos target, String name) {
        BlockPos from = contraption instanceof ContraptionPlace p ? p.rysclaims$place() : null;
        if (from == null) return at(level, null, null, target, name, FAR);
        return at(level, from, null, target, name + " @" + from.getX() + " " + from.getY() + " " + from.getZ(), FAR);
    }

    /** Поведение блок-сущности Create ({@code BlockEntityBehaviour}): его блок-сущность из поля {@code blockEntity}. */
    public static @Nullable BlockEntity behaviourEntity(Object behaviour) {
        try {
            Field f = OWNER.get(behaviour.getClass());
            return f != null && f.get(behaviour) instanceof BlockEntity be ? be : null;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    /** Машина, которая сейчас работает в этом потоке (механическая рука Create в своём тике), или null. */
    public static @Nullable BlockEntity actor() {
        return ACTOR.get();
    }

    public static void actor(@Nullable Object be) {
        ACTOR.set(be instanceof BlockEntity b ? b : null);
    }

    /**
     * Фейковый игрок сейчас действует за машину, которую уже проверили по месту (плуг Create: правило постройки
     * решено в начале шага, а вспашка идёт событиями от общего профиля «Plough»). {@link Protection} ему не отказывает.
     */
    public static boolean vouched() {
        return VOUCHED.get() == Boolean.TRUE;
    }

    public static void vouch(boolean on) {
        VOUCHED.set(on ? Boolean.TRUE : null);
    }

    private static final ThreadLocal<BlockEntity> ACTOR = new ThreadLocal<>();
    private static final ThreadLocal<Boolean> VOUCHED = new ThreadLocal<>();

    private static final ClassValue<Field[]> CONTEXT = new ClassValue<>() {
        @Override
        protected Field[] computeValue(Class<?> type) {
            try {
                return new Field[] {type.getField("world"), type.getField("contraption"), type.getField("state")};
            } catch (NoSuchFieldException e) {
                throw new IllegalStateException(e);
            }
        }
    };

    private static final ClassValue<Field> OWNER = new ClassValue<>() {
        @Override
        protected Field computeValue(Class<?> type) {
            try {
                return type.getField("blockEntity");
            } catch (NoSuchFieldException e) {
                return null;
            }
        }
    };

    /** Пчела (Productive Bees): место это её улей, хозяина нет; без улья место неизвестно. */
    public static boolean bee(Object self, BlockPos target) {
        if (!(self instanceof Entity bee)) return true;
        BlockPos hive = bee instanceof Bee b ? b.getHivePos() : null;
        ResourceLocation id = BuiltInRegistries.ENTITY_TYPE.getKey(bee.getType());
        return at(bee.level(), hive, null, target, id.toString(), FAR);
    }

    /**
     * Вспышка маны Botania без хозяина (из распределителя; линза бура ломает {@code destroyBlock} после
     * {@code Projectile.mayInteract}): место это блок-источник вспышки {@code getBurstSourceBlockPos()}. Не вспышка: true.
     */
    public static boolean burst(Entity projectile, Level level, BlockPos target) {
        if (!works.repka.rysclaims.compat.Links.on(works.repka.rysclaims.compat.Links.Link.BOTANIA)) return true;
        Method m = BURST.get(projectile.getClass());
        if (m == null) return true;
        BlockPos source;
        try {
            source = m.invoke(projectile) instanceof BlockPos p ? p : null;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return true;
        }
        if (source != null && level.isOutsideBuildHeight(source)) source = null; // ManaBurst.NO_SOURCE
        return at(level, source, null, target, "botania:mana_burst", FAR);
    }

    private static final ClassValue<Method> BURST = new ClassValue<>() {
        @Override
        protected Method computeValue(Class<?> type) {
            try {
                return type.getMethod("getBurstSourceBlockPos");
            } catch (NoSuchMethodException e) {
                return null;
            }
        }
    };

    /** Сущность-работник (дух Occultism): профиль её хозяина, место её позиция, дальность обычная. */
    public static boolean entity(@Nullable Entity worker, BlockPos target) {
        if (worker == null) return true;
        UUID owner = worker instanceof OwnableEntity own ? own.getOwnerUUID() : null;
        ResourceLocation id = BuiltInRegistries.ENTITY_TYPE.getKey(worker.getType());
        return at(worker.level(), worker.blockPosition(), owner, target, id.toString(), ClaimService.MACHINE_REACH);
    }

    /**
     * Машина в {@code from} (null: место неизвестно) с профилем {@code profile} (null: нет) действует в {@code target}.
     *
     * @param reach дальность «рядом» в чанках
     */
    public static boolean at(Level level, @Nullable BlockPos from, @Nullable UUID profile, BlockPos target, String name, int reach) {
        if (from != null && ((from.getX() ^ target.getX()) >> 4) == 0 && ((from.getZ() ^ target.getZ()) >> 4) == 0) return true;
        ClaimService s = service(level);
        if (s == null) return true;
        String dim = Loader.id(level.dimension());
        if (!s.data().anyIn(dim)) return true;
        BlockPos t = SableLand.toWorld(level, target);
        long targetChunk = Chunks.pack(t.getX() >> 4, t.getZ() >> 4);
        long fromChunk = Long.MIN_VALUE;
        if (from != null) {
            BlockPos f = SableLand.toWorld(level, from);
            fromChunk = Chunks.pack(f.getX() >> 4, f.getZ() >> 4);
        }
        if (s.canMachine(dim, fromChunk, profile, targetChunk, Rights.Action.BUILD, reach)) return true;
        log(s, dim, targetChunk, profile, name, t);
        return false;
    }

    /** Машина меняет целую область (пространственный ввод-вывод AE2): нельзя, если хоть один чанк области ей закрыт. */
    public static boolean area(Level level, BlockPos min, BlockPos max, @Nullable UUID profile, @Nullable BlockPos from, String name) {
        ClaimService s = service(level);
        if (s == null) return true;
        String dim = Loader.id(level.dimension());
        if (!s.data().anyIn(dim)) return true;
        long fromChunk = from == null ? Long.MIN_VALUE : Chunks.pack(from.getX() >> 4, from.getZ() >> 4);
        int x0 = Math.min(min.getX(), max.getX()) >> 4, x1 = Math.max(min.getX(), max.getX()) >> 4;
        int z0 = Math.min(min.getZ(), max.getZ()) >> 4, z1 = Math.max(min.getZ(), max.getZ()) >> 4;
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                long chunk = Chunks.pack(x, z);
                if (s.data().owner(dim, chunk) == null) continue;
                if (!s.canMachine(dim, fromChunk, profile, chunk, Rights.Action.BUILD, FAR)) {
                    log(s, dim, chunk, profile, name, new BlockPos((x << 4) + 8, min.getY(), (z << 4) + 8));
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Разборка постройки (Create, Sable) положит блоки в {@code targets}: команда первого закрытого ей чанка или null, если
     * все чанки открыты. Правило то же, что у машины с известным местом ({@code from}: блок управления или дом постройки),
     * каждый чанк проверяется один раз. Отказ уходит в журнал хозяев одной строкой.
     */
    public static @Nullable UUID disassembly(Level level, @Nullable BlockPos from, Iterable<BlockPos> targets, String name) {
        ClaimService s = service(level);
        if (s == null) return null;
        String dim = Loader.id(level.dimension());
        if (!s.data().anyIn(dim)) return null;
        long fromChunk = Long.MIN_VALUE;
        if (from != null) {
            BlockPos f = SableLand.toWorld(level, from);
            fromChunk = Chunks.pack(f.getX() >> 4, f.getZ() >> 4);
        }
        it.unimi.dsi.fastutil.longs.LongOpenHashSet seen = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
        for (BlockPos raw : targets) {
            BlockPos target = SableLand.toWorld(level, raw);
            long chunk = Chunks.pack(target.getX() >> 4, target.getZ() >> 4);
            if (!seen.add(chunk) || chunk == fromChunk) continue;
            UUID owner = s.data().owner(dim, chunk);
            if (owner == null) continue;
            if (!s.canMachine(dim, fromChunk, null, chunk, Rights.Action.BUILD, FAR)) {
                log(s, dim, chunk, null, name, target);
                return owner;
            }
        }
        return null;
    }

    /** Взрыв из точки {@code center} без хозяина (реактор Draconic) убирает блок {@code target}: правило строки 15. */
    public static boolean explosion(Level level, BlockPos center, BlockPos target) {
        if (((center.getX() ^ target.getX()) >> 4) == 0 && ((center.getZ() ^ target.getZ()) >> 4) == 0) return true;
        ClaimService s = service(level);
        if (s == null) return true;
        String dim = Loader.id(level.dimension());
        if (!s.data().anyIn(dim)) return true;
        return s.ownerlessExplosion(dim, Chunks.pack(center.getX() >> 4, center.getZ() >> 4), Chunks.pack(target.getX() >> 4, target.getZ() >> 4));
    }

    private static void log(ClaimService s, String dim, long chunk, @Nullable UUID profile, String name, BlockPos at) {
        UUID owner = s.data().owner(dim, chunk);
        DenyLog log = ClaimsServer.log();
        if (owner != null && log != null) log.add(owner, profile, name, true, ClaimService.Reason.MACHINE, dim, at.getX(), at.getY(), at.getZ());
    }

    private static String name(BlockEntity be) {
        ResourceLocation id = BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(be.getType());
        BlockPos p = be.getBlockPos();
        return (id == null ? be.getClass().getSimpleName() : id.toString()) + " " + p.getX() + " " + p.getY() + " " + p.getZ();
    }
}

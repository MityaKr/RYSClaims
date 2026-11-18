package works.repka.rysclaims.server;

import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.jetbrains.annotations.Nullable;
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

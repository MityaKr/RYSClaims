package works.repka.rysclaims.server;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;
import works.repka.rysclaims.compat.sable.SableLand;
import works.repka.rysclaims.data.Chunks;
import works.repka.rysclaims.data.Rights;

/**
 * Через границу земли без игрока (план, раздел 11, строки 19, 20): вода и лава, огонь, молния. Зовутся из перехватов
 * {@code mixin/} тысячи раз за тик, поэтому сначала сравниваются номера чанков двух позиций: внутри одного чанка ответ
 * сразу, поиск в таблице только на границе чанков и только если в измерении есть земля.
 */
public final class Borders {
    private Borders() {}

    private static @Nullable ClaimService service(@Nullable Object level) {
        if (!(level instanceof ServerLevel) || !ClaimsServer.running()) return null;
        return ClaimsServer.service();
    }

    /** Жидкость из {@code from} в {@code to}: на землю другой команды только с настройкой сервера. */
    public static boolean fluid(BlockGetter level, BlockPos from, BlockPos to) {
        if (((from.getX() ^ to.getX()) >> 4) == 0 && ((from.getZ() ^ to.getZ()) >> 4) == 0) return true;
        ClaimService s = service(level);
        if (s == null) return true;
        return cross(s, (Level) level, from, to, s.settings().fluidsCross);
    }

    /** Огонь из {@code from} в {@code to}: на землю другой команды только с настройкой сервера. */
    public static boolean fire(Level level, BlockPos from, BlockPos to) {
        if (((from.getX() ^ to.getX()) >> 4) == 0 && ((from.getZ() ^ to.getZ()) >> 4) == 0) return true;
        ClaimService s = service(level);
        if (s == null) return true;
        return cross(s, level, from, to, s.settings().fireCross);
    }

    private static boolean cross(ClaimService s, Level level, BlockPos from, BlockPos to, boolean allowed) {
        String dim = Loader.id(level.dimension());
        if (!s.data().anyIn(dim)) return true;
        BlockPos wf = SableLand.toWorld(level, from), wt = SableLand.toWorld(level, to);
        return s.mayCross(dim, Chunks.pack(wf.getX() >> 4, wf.getZ() >> 4), Chunks.pack(wt.getX() >> 4, wt.getZ() >> 4), allowed);
    }

    /**
     * Огонь от молнии: молния трезубца с «Громовержцем» судится правом «Строить» её игрока, любая молния ещё и как огонь
     * из точки удара.
     */
    public static boolean lightning(Level level, BlockPos strike, BlockPos fire, @Nullable net.minecraft.world.entity.player.Player cause) {
        if (cause != null && !Protection.allowed(cause, level, fire, Rights.Action.BUILD)) return false;
        return fire(level, strike, fire);
    }
}

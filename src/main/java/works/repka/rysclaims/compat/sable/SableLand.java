package works.repka.rysclaims.compat.sable;

import java.lang.reflect.Method;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Position;
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import works.repka.rysclaims.RYSClaims;
import works.repka.rysclaims.data.ClaimData;
import works.repka.rysclaims.data.Chunks;
import works.repka.rysclaims.server.ClaimsServer;

/**
 * Sable и Create Aeronautics (план, раздел 11, строка 34; R0, раздел 3). Блоки летающей постройки лежат в «участке» того же
 * измерения, далеко от начала координат: без поправки любая проверка решала бы по пустой земле участка. Поэтому позиция в
 * участке переводится в мировую ({@code projectOutOfSubLevel}), а у постройки есть «дом»: команда земли, где её собрали.
 *
 * <p>Sable берётся через компаньон {@code dev.ryanhcode.sable.companion.SableCompanion.INSTANCE} (лежит jarjar внутри Sable;
 * без Sable класса нет или он отвечает null) отражением: жёсткой зависимости нет. Сначала дешёвая проверка координат: вне
 * сетки участков ({@link Chunks#sablePlot}) отражение не зовётся.
 */
public final class SableLand {
    private static boolean tried;
    private static @Nullable Object companion;
    private static @Nullable Method getContaining, project, uniqueId, splitFrom;

    private SableLand() {}

    private static boolean ready() {
        if (!tried) {
            tried = true;
            if (!works.repka.rysclaims.compat.Links.on(works.repka.rysclaims.compat.Links.Link.SABLE)) return false;
            try {
                Class<?> type = Class.forName("dev.ryanhcode.sable.companion.SableCompanion");
                Object inst = type.getField("INSTANCE").get(null);
                getContaining = type.getMethod("getContaining", Level.class, Vec3i.class);
                project = type.getMethod("projectOutOfSubLevel", Level.class, Position.class);
                uniqueId = Class.forName("dev.ryanhcode.sable.companion.SubLevelAccess").getMethod("getUniqueId");
                companion = inst;
                RYSClaims.LOG.info("RYSClaims: Sable found, blocks of flying contraptions are judged by the land below them");
            } catch (ReflectiveOperationException | LinkageError e) {
                companion = null;
            }
        }
        return companion != null;
    }

    /** Компаньон Sable найден (и связка включена). */
    public static boolean available() {
        return ready();
    }

    /** Подуровень, в участке которого лежит {@code pos}, или null (не участок, нет Sable, участок пуст). */
    private static @Nullable Object subLevel(Level level, BlockPos pos) {
        if (!Chunks.sablePlot(pos.getX() >> 4, pos.getZ() >> 4) || !ready()) return null;
        try {
            return getContaining.invoke(companion, level, pos);
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    /** Мировая позиция блока: для блока постройки Sable место, над которым она сейчас; иначе та же позиция (тот же объект). */
    public static BlockPos toWorld(Level level, BlockPos pos) {
        Object sub = subLevel(level, pos);
        if (sub == null) return pos;
        try {
            Vec3 w = (Vec3) project.invoke(companion, level, Vec3.atCenterOf(pos));
            return BlockPos.containing(w);
        } catch (ReflectiveOperationException | ClassCastException e) {
            return pos;
        }
    }

    /** Команда-дом постройки, в участке которой лежит {@code pos}, или null. Отколовшаяся часть наследует дом родителя. */
    public static @Nullable UUID home(Level level, BlockPos pos) {
        Object sub = subLevel(level, pos);
        if (sub == null || !ClaimsServer.running()) return null;
        ClaimData data = ClaimsServer.service().data();
        UUID id = id(sub);
        if (id == null) return null;
        UUID home = data.home(id);
        if (home != null) return home;
        UUID parent = splitParent(sub);
        if (parent == null) return null;
        home = data.home(parent);
        if (home != null) {
            data.home(id, home);
            ClaimsServer.markDirty();
        }
        return home;
    }

    /** UUID подуровня (объект {@code SubLevelAccess}, например результат сборки) или null. */
    public static @Nullable UUID id(@Nullable Object subLevel) {
        if (subLevel == null || !ready()) return null;
        try {
            return (UUID) uniqueId.invoke(subLevel);
        } catch (ReflectiveOperationException | ClassCastException | IllegalArgumentException e) {
            return null;
        }
    }

    private static @Nullable UUID splitParent(Object sub) {
        try {
            if (splitFrom == null) splitFrom = sub.getClass().getMethod("getSplitFromSubLevel");
            return (UUID) splitFrom.invoke(sub);
        } catch (ReflectiveOperationException | ClassCastException e) {
            return null;
        }
    }
}

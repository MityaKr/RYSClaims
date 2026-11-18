package works.repka.rysclaims.compat.gravestone;

import java.lang.reflect.Method;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.jetbrains.annotations.Nullable;
import works.repka.rysclaims.compat.Links;

/**
 * Могила Gravestone 1.0.40 (разбор скептика 18.11, пункт 3). На сервере RepYS вещи возвращаются только поломкой могилы
 * ({@code break_pickup = true}, {@code sneak_pickup = false}): {@code GraveStoneBlock.onDestroyedByPlayer} раскладывает
 * их в инвентарь того, кто сломал. Поэтому хозяин могилы ломает её на любой земле, а хозяева земли видят это в журнале.
 *
 * <p>Хозяин читается отражением ({@code GraveStoneTileEntity.getDeath().getPlayerUUID()}, проверено CFR), типы мода при
 * сборке не нужны. Клиент тоже знает хозяина: {@code getUpdateTag} могилы шлёт всю запись смерти.
 */
public final class Graves {
    private static final String PACKAGE = "de.maxhenkel.gravestone.";
    private static final UUID EMPTY = new UUID(0L, 0L);
    private static volatile @Nullable Method death, uuid;

    private Graves() {}

    /** Могила этого игрока стоит в точке (связка включена, хозяин могилы известен и это он). */
    public static boolean own(@Nullable Player player, Level level, BlockPos pos) {
        if (player == null || !Links.on(Links.Link.GRAVESTONE)) return false;
        UUID owner = owner(level.getBlockEntity(pos));
        return owner != null && owner.equals(player.getUUID());
    }

    /** Хозяин могилы или null (не могила, пустая могила, другая версия мода). */
    public static @Nullable UUID owner(@Nullable BlockEntity be) {
        if (be == null || !be.getClass().getName().startsWith(PACKAGE)) return null;
        try {
            Method d = death;
            if (d == null || !d.getDeclaringClass().isInstance(be)) death = d = find(be.getClass(), "getDeath");
            if (d == null) return null;
            Object rec = d.invoke(be);
            if (rec == null) return null;
            Method u = uuid;
            if (u == null || !u.getDeclaringClass().isInstance(rec)) uuid = u = find(rec.getClass(), "getPlayerUUID");
            if (u == null || !(u.invoke(rec) instanceof UUID id) || id.equals(EMPTY)) return null;
            return id;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            return null;
        }
    }

    private static @Nullable Method find(Class<?> type, String name) {
        try {
            return type.getMethod(name);
        } catch (NoSuchMethodException e) {
            return null;
        }
    }
}

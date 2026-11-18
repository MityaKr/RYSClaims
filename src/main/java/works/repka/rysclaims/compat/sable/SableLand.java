package works.repka.rysclaims.compat.sable;

import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

/** Sable: позиция участка в мировую и «дом» постройки. Заготовка: без Sable позиция та же. */
public final class SableLand {
    private SableLand() {}

    public static BlockPos toWorld(Level level, BlockPos pos) {
        return pos;
    }

    public static @Nullable UUID home(Level level, BlockPos pos) {
        return null;
    }
}

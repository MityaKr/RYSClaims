package works.repka.rysclaims.compat.create;

import net.minecraft.core.BlockPos;
import org.jetbrains.annotations.Nullable;

/**
 * Место постройки Create (контрапции): блок управления, который её собрал (подшипник, поршень, портал, сборщик
 * вагонетки, тележка поезда), иначе {@code anchor} самой постройки. Его дописывает перехват {@code Contraption}
 * ({@code mixin/compat/CreateContraptionMixin}) и хранит в NBT постройки: так поршень у границы не может «унести»
 * якорь на чужую землю длинным штоком.
 */
public interface ContraptionPlace {
    @Nullable BlockPos rysclaims$place();

    void rysclaims$controller(BlockPos pos);
}

package works.repka.rysclaims.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.LevelAccessor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import works.repka.rysclaims.server.Borders;

/**
 * Скалк (план, раздел 11, строка 24): заряд катализатора не переходит на землю другой команды, чем катализатор
 * ({@code pos} метода это место катализатора). Вместе с настройкой огня.
 */
@Mixin(targets = "net.minecraft.world.level.block.SculkSpreader$ChargeCursor")
abstract class SculkMixin {
    @WrapOperation(method = "update", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/level/block/SculkSpreader$ChargeCursor;getValidMovementPos(Lnet/minecraft/world/level/LevelAccessor;Lnet/minecraft/core/BlockPos;Lnet/minecraft/util/RandomSource;)Lnet/minecraft/core/BlockPos;"))
    private BlockPos rysclaims$border(LevelAccessor level, BlockPos from, RandomSource random, Operation<BlockPos> original,
                                      @Local(argsOnly = true) BlockPos catalyst) {
        BlockPos to = original.call(level, from, random);
        return to == null || Borders.sculk(level, catalyst, to) ? to : null;
    }
}

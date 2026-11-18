package works.repka.rysclaims.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.FireBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import works.repka.rysclaims.server.Borders;

/**
 * Огонь через границу земли (план, раздел 11, строка 20): {@code FireBlock} событий не зовёт. Выгорание соседа
 * ({@code checkBurnOut}: сжечь или поджечь блок рядом) и прыжок огня в {@code tick} судятся как переход из клетки огня.
 */
@Mixin(FireBlock.class)
abstract class FireBlockMixin {
    @Inject(method = "checkBurnOut", at = @At("HEAD"), cancellable = true)
    private void rysclaims$burn(Level level, BlockPos pos, int chance, RandomSource random, int age, Direction face, CallbackInfo ci) {
        // face смотрит от соседа на огонь: огонь стоит в pos.relative(face)
        if (!Borders.fire(level, pos.relative(face), pos)) ci.cancel();
    }

    @WrapOperation(method = "tick", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/server/level/ServerLevel;setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;I)Z"))
    private boolean rysclaims$spread(ServerLevel level, BlockPos target, BlockState state, int flags, Operation<Boolean> original,
                                     @Local(argsOnly = true) BlockPos fire) {
        if (!Borders.fire(level, fire, target)) return false;
        return original.call(level, target, state, flags);
    }
}

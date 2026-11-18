package works.repka.rysclaims.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import works.repka.rysclaims.server.Borders;

/** Вода и лава через границу земли (план, раздел 11, строка 19): событий у растекания нет. */
@Mixin(FlowingFluid.class)
abstract class FlowingFluidMixin {
    @Inject(method = "canSpreadTo", at = @At("HEAD"), cancellable = true)
    private void rysclaims$border(BlockGetter level, BlockPos from, BlockState fromState, Direction dir, BlockPos to, BlockState toState,
                                  FluidState toFluid, Fluid fluid, CallbackInfoReturnable<Boolean> cir) {
        if (!Borders.fluid(level, from, to)) cir.setReturnValue(false);
    }
}

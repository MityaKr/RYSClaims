package works.repka.rysclaims.mixin.compat;

import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import works.repka.rysclaims.server.Machines;

/**
 * Бур, пила, плуг и каток на постройке Create 6.0.10 ({@code BlockBreakingMovementBehaviour}): ломают
 * {@code BlockHelper.destroyBlock} без игрока и событий (R0, раздел 1.1). Отмена в начале {@code visitNewPosition}
 * (постройка не встаёт ломать, упирается как в стену) и {@code destroyBlock} (дроп собирается внутри, дюпа нет).
 * Место это якорь постройки.
 */
@Pseudo
@Mixin(targets = "com.simibubi.create.content.kinetics.base.BlockBreakingMovementBehaviour", remap = false)
abstract class CreateBreakerMixin {
    @Inject(method = {"visitNewPosition", "destroyBlock"}, at = @At("HEAD"), cancellable = true, require = 0)
    private void rysclaims$visit(@Coerce Object context, BlockPos pos, CallbackInfo ci) {
        if (!Machines.contraption(context, pos)) ci.cancel();
    }
}

package works.repka.rysclaims.mixin.compat;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import works.repka.rysclaims.server.Machines;

/**
 * Неподвижные бур и пила Create 6.0.10 ({@code BlockBreakingKineticBlockEntity}): {@code tick} спрашивает
 * {@code canBreak(состояние, твёрдость)} перед каждым шагом и ломает {@code BlockHelper.destroyBlock} без игрока.
 * «Нельзя» гасит трещины и останавливает машину до следующей проверки; пила зовёт этот же метод через {@code super}.
 * Место это блок машины, цель соседний блок {@code breakingPos}.
 */
@Pseudo
@Mixin(targets = "com.simibubi.create.content.kinetics.base.BlockBreakingKineticBlockEntity", remap = false)
abstract class CreateKineticBreakerMixin {
    @Shadow protected BlockPos breakingPos;

    @Inject(method = "canBreak", at = @At("HEAD"), cancellable = true, require = 0)
    private void rysclaims$canBreak(BlockState state, float hardness, CallbackInfoReturnable<Boolean> cir) {
        if (!Machines.blockEntity(this, breakingPos)) cir.setReturnValue(false);
    }
}

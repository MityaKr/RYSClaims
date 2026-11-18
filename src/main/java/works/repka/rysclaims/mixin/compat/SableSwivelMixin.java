package works.repka.rysclaims.mixin.compat;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import works.repka.rysclaims.compat.sable.SableDisassembly;

/**
 * Поворотный подшипник Simulated 1.3.2: {@code SwivelBearingBlockEntity.disassemble()} снимает пластину и ставит подуровень
 * в мир (CFR). Над землёй, закрытой постройке, разборка отменяется в начале, подшипник остаётся собранным.
 */
@Pseudo
@Mixin(targets = "dev.simulated_team.simulated.content.blocks.swivel_bearing.SwivelBearingBlockEntity", remap = false)
abstract class SableSwivelMixin {
    @Inject(method = "disassemble()V", at = @At("HEAD"), cancellable = true, require = 0)
    private void rysclaims$refuse(CallbackInfo ci) {
        if (SableDisassembly.swivel(this)) ci.cancel();
    }
}

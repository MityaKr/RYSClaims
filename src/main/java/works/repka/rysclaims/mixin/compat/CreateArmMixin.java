package works.repka.rysclaims.mixin.compat;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import works.repka.rysclaims.server.Machines;

/**
 * Механическая рука Create 6.0.10 (план, раздел 12, риск 3): берёт и кладёт предметы через {@code capabilities}, без
 * игрока и событий, в радиусе до 5 блоков, в том числе через границу чанка. Рука запоминается на время своего тика
 * ({@link Machines#actor}), а {@code CreateArmPointMixin} отвечает «точка недействительна» для клетки, закрытой её месту.
 */
@Pseudo
@Mixin(targets = "com.simibubi.create.content.kinetics.mechanicalArm.ArmBlockEntity", remap = false)
abstract class CreateArmMixin {
    @Inject(method = "tick", at = @At("HEAD"), require = 0)
    private void rysclaims$start(CallbackInfo ci) {
        Machines.actor(this);
    }

    @Inject(method = "tick", at = @At("RETURN"), require = 0)
    private void rysclaims$end(CallbackInfo ci) {
        Machines.actor(null);
    }
}

package works.repka.rysclaims.mixin.compat;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import works.repka.rysclaims.compat.create.CreateDisassembly;

/**
 * Разборка постройки Create 6.0.10 блоком управления (подшипник и ветряк, часовой подшипник, поршень, шкив, лифт): если
 * блоки легли бы на землю, закрытую постройке, {@code disassemble()} отменяется, постройка остаётся собранной
 * ({@link CreateDisassembly}). Все цели проверены CFR: метод без аргументов, зовётся из тика по {@code assembleNextTick} и
 * из {@code remove()} (там не отменяется).
 */
@Pseudo
@Mixin(targets = {
    "com.simibubi.create.content.contraptions.bearing.MechanicalBearingBlockEntity",
    "com.simibubi.create.content.contraptions.bearing.ClockworkBearingBlockEntity",
    "com.simibubi.create.content.contraptions.piston.MechanicalPistonBlockEntity",
    "com.simibubi.create.content.contraptions.pulley.PulleyBlockEntity",
    "com.simibubi.create.content.contraptions.elevator.ElevatorPulleyBlockEntity"
}, remap = false)
abstract class CreateDisassembleMixin {
    @Inject(method = "disassemble()V", at = @At("HEAD"), cancellable = true, require = 0)
    private void rysclaims$refuse(CallbackInfo ci) {
        if (CreateDisassembly.refuseController(this)) ci.cancel();
    }
}

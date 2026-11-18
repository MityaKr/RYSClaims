package works.repka.rysclaims.mixin.compat;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import works.repka.rysclaims.compat.create.CreateDisassembly;

/**
 * Портал Create 6.0.10: {@code GantryContraptionEntity.disassemble()} зовётся из тика самой сущности (нет вала, вал запитан,
 * упёрлась). Над закрытой землёй разборка отменяется, портал стоит собранным ({@link CreateDisassembly}).
 */
@Pseudo
@Mixin(targets = "com.simibubi.create.content.contraptions.gantry.GantryContraptionEntity", remap = false)
abstract class CreateGantryMixin {
    @Inject(method = "disassemble()V", at = @At("HEAD"), cancellable = true, require = 0)
    private void rysclaims$refuse(CallbackInfo ci) {
        if (CreateDisassembly.refuseEntity(this)) ci.cancel();
    }
}

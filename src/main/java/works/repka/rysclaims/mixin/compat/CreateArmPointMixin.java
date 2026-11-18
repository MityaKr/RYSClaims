package works.repka.rysclaims.mixin.compat;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import works.repka.rysclaims.server.Machines;

/**
 * Точка механической руки Create 6.0.10: рука трогает точку только после {@code isValid()} (поиск, взять, положить,
 * проверено по байткоду {@code ArmBlockEntity}). Во время тика руки точка на земле, закрытой месту руки,
 * недействительна: рука её пропускает, предмет остаётся у руки.
 */
@Pseudo
@Mixin(targets = "com.simibubi.create.content.kinetics.mechanicalArm.ArmInteractionPoint", remap = false)
abstract class CreateArmPointMixin {
    @Shadow protected Level level;
    @Shadow @Final protected BlockPos pos;

    @Inject(method = "isValid", at = @At("HEAD"), cancellable = true, require = 0)
    private void rysclaims$valid(CallbackInfoReturnable<Boolean> cir) {
        BlockEntity arm = Machines.actor();
        if (arm != null && level != null && !Machines.blockEntity(arm, level, pos)) cir.setReturnValue(false);
    }
}

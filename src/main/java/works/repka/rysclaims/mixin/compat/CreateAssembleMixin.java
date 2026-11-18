package works.repka.rysclaims.mixin.compat;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import works.repka.rysclaims.compat.create.ContraptionPlace;

/**
 * Якорь постройки Create: {@code assemble(уровень, позиция)} каждого вида получает позицию своего блока управления
 * (подшипник, поршень, портал, шкив, лифт, сборщик вагонетки, тележка поезда). {@code anchor} самой постройки бывает
 * дальше (у поршня конец штока), поэтому место запоминается здесь, до поиска блоков.
 */
@Pseudo
@Mixin(targets = {
    "com.simibubi.create.content.contraptions.bearing.BearingContraption",
    "com.simibubi.create.content.contraptions.bearing.ClockworkContraption",
    "com.simibubi.create.content.contraptions.bearing.StabilizedContraption",
    "com.simibubi.create.content.contraptions.piston.PistonContraption",
    "com.simibubi.create.content.contraptions.gantry.GantryContraption",
    "com.simibubi.create.content.contraptions.pulley.PulleyContraption",
    "com.simibubi.create.content.contraptions.elevator.ElevatorContraption",
    "com.simibubi.create.content.contraptions.mounted.MountedContraption",
    "com.simibubi.create.content.trains.entity.CarriageContraption"
}, remap = false)
abstract class CreateAssembleMixin {
    @Inject(method = "assemble", at = @At("HEAD"), require = 0)
    private void rysclaims$assemble(Level level, BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        if ((Object) this instanceof ContraptionPlace place) place.rysclaims$controller(pos);
    }
}

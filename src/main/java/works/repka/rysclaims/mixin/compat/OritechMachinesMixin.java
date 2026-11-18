package works.repka.rysclaims.mixin.compat;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import works.repka.rysclaims.server.Machines;

/**
 * Oritech 1.2.13 (R0, раздел 1.2): разрушитель и установщик ({@code finishBlockWork}: работа идёт в столбце под головкой,
 * тот же чанк, что {@code pos}) и лазер ({@code finishBlockBreaking}) ломают и ставят без событий, профиль случайный.
 * Отмена в начале метода: дроп ещё не собран, предмет ещё не потрачен. Место это блок машины.
 */
@Pseudo
@Mixin(targets = {
    "rearth.oritech.block.entity.interaction.DestroyerBlockEntity",
    "rearth.oritech.block.entity.interaction.PlacerBlockEntity"
}, remap = false)
abstract class OritechMachinesMixin {
    @Inject(method = "finishBlockWork(Lnet/minecraft/core/BlockPos;)V", at = @At("HEAD"), cancellable = true, require = 0)
    private void rysclaims$work(BlockPos pos, CallbackInfo ci) {
        Object self = this;
        if (self instanceof BlockEntity be && !Machines.blockEntity(be, be.getLevel(), pos)) ci.cancel();
    }
}

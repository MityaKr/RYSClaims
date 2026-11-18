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

/** Лазер Oritech: бьёт далеко, ломает через {@code destroyBlock} без событий. Место это блок лазера, дальность любая. */
@Pseudo
@Mixin(targets = "rearth.oritech.block.entity.interaction.LaserArmBlockEntity", remap = false)
abstract class OritechLaserMixin {
    @Inject(method = "finishBlockBreaking(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;)V",
        at = @At("HEAD"), cancellable = true, require = 0)
    private void rysclaims$break(BlockPos pos, BlockState state, CallbackInfo ci) {
        Object self = this;
        if (self instanceof BlockEntity be && !Machines.blockEntity(be, be.getLevel(), pos)) ci.cancel();
    }
}

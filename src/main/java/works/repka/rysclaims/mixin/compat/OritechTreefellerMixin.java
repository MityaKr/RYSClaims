package works.repka.rysclaims.mixin.compat;

import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import works.repka.rysclaims.server.Machines;

/** Лесоруб Oritech убирает брёвна {@code setBlockAndUpdate} без событий: чужое дерево не трогает. */
@Pseudo
@Mixin(targets = "rearth.oritech.block.entity.interaction.TreefellerBlockEntity", remap = false)
abstract class OritechTreefellerMixin {
    @Inject(method = "breakTreeBlock(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/InteractionResult;",
        at = @At("HEAD"), cancellable = true, require = 0)
    private void rysclaims$fell(BlockState state, BlockPos pos, CallbackInfoReturnable<InteractionResult> cir) {
        Object self = this;
        if (self instanceof BlockEntity be && !Machines.blockEntity(be, be.getLevel(), pos)) cir.setReturnValue(InteractionResult.FAIL);
    }
}

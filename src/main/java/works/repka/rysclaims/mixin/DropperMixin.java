package works.repka.rysclaims.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.DispenserBlock;
import net.minecraft.world.level.block.DropperBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import works.repka.rysclaims.server.Borders;

/** Выбрасыватель переопределяет {@code dispenseFrom}: в контейнер и на землю за границей не выбрасывает (строки 26, 28). */
@Mixin(DropperBlock.class)
abstract class DropperMixin {
    @Inject(method = "dispenseFrom", at = @At("HEAD"), cancellable = true)
    private void rysclaims$border(ServerLevel level, BlockState state, BlockPos pos, CallbackInfo ci) {
        if (!state.hasProperty(DispenserBlock.FACING)) return;
        if (!Borders.hopper(level, pos, pos.relative(state.getValue(DispenserBlock.FACING)))) {
            level.levelEvent(1001, pos, 0);
            ci.cancel();
        }
    }
}

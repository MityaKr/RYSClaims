package works.repka.rysclaims.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.DispenserBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import works.repka.rysclaims.server.Borders;

/**
 * Раздатчик (план, раздел 11, строка 28): цель в чанке другой команды, раздатчик щёлкает впустую (звук осечки). Ставит
 * блоки, льёт вёдра, стрижёт, кладёт костную муку: всё это идёт отсюда. Вместе с настройкой воронок.
 */
@Mixin(DispenserBlock.class)
abstract class DispenserMixin {
    @Inject(method = "dispenseFrom", at = @At("HEAD"), cancellable = true)
    private void rysclaims$border(ServerLevel level, BlockState state, BlockPos pos, CallbackInfo ci) {
        if (!state.hasProperty(DispenserBlock.FACING)) return;
        if (!Borders.hopper(level, pos, pos.relative(state.getValue(DispenserBlock.FACING)))) {
            level.levelEvent(1001, pos, 0);
            ci.cancel();
        }
    }
}

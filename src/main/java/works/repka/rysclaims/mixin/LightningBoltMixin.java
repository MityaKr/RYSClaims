package works.repka.rysclaims.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import works.repka.rysclaims.server.Borders;

/** Огонь от молнии (план, раздел 11, строка 20): из точки удара как огонь, у «Громовержца» ещё право его игрока. */
@Mixin(LightningBolt.class)
abstract class LightningBoltMixin {
    @WrapOperation(method = "spawnFire", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/level/Level;setBlockAndUpdate(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;)Z"))
    private boolean rysclaims$fire(Level level, BlockPos pos, BlockState state, Operation<Boolean> original) {
        LightningBolt self = (LightningBolt) (Object) this;
        if (!Borders.lightning(level, self.blockPosition(), pos, self.getCause())) return false;
        return original.call(level, pos, state);
    }
}

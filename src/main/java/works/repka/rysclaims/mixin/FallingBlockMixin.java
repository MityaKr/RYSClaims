package works.repka.rysclaims.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import works.repka.rysclaims.server.Borders;

/**
 * Песок и наковальня через границу (план, раздел 11, строка 21): падающий блок, начавший падение не на земле команды,
 * на её землю не ложится; игра тогда роняет его предметом (ветка «не поставился» в {@code tick}). Блок без выпадения
 * просто пропадает. Вместе с настройкой поршней.
 */
@Mixin(FallingBlockEntity.class)
abstract class FallingBlockMixin {
    @Shadow
    public boolean dropItem;

    @Shadow
    public abstract BlockPos getStartPos();

    @WrapOperation(method = "tick", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/level/Level;setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;I)Z"))
    private boolean rysclaims$land(Level level, BlockPos pos, BlockState state, int flags, Operation<Boolean> original) {
        if (!level.isClientSide && !Borders.falling(level, getStartPos(), pos)) {
            if (!dropItem) ((FallingBlockEntity) (Object) this).discard();
            return false;
        }
        return original.call(level, pos, state, flags);
    }
}

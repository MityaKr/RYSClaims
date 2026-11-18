package works.repka.rysclaims.mixin.compat;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import works.repka.rysclaims.server.Machines;

/**
 * Воронки, упаковщик, умный наблюдатель и прочие Create 6.0.10, которые берут соседний инвентарь через
 * {@code CapManipulationBehaviourBase.findNewCapability} (план, раздел 12, риск 3). Соседняя клетка за границей
 * чанка, закрытая месту машины, отвечает «не загружена»: инвентаря нет, поиск повторится в {@code lazyTick}.
 * Лотки ({@code ChuteBlockEntity}) берут только сверху и снизу, это всегда тот же чанк.
 */
@Pseudo
@Mixin(targets = "com.simibubi.create.foundation.blockEntity.behaviour.inventory.CapManipulationBehaviourBase", remap = false)
abstract class CreateInventoryMixin {
    @WrapOperation(method = "findNewCapability", require = 0, at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/level/Level;isLoaded(Lnet/minecraft/core/BlockPos;)Z"))
    private boolean rysclaims$loaded(Level level, BlockPos pos, Operation<Boolean> original) {
        BlockEntity be = Machines.behaviourEntity(this);
        if (be != null && !Machines.blockEntity(be, level, pos)) return false;
        return original.call(level, pos);
    }
}

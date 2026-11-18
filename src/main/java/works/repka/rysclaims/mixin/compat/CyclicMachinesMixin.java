package works.repka.rysclaims.mixin.compat;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import works.repka.rysclaims.server.Machines;

/**
 * Cyclic 1.14.2 (R0, раздел 1.2): ломатель, установщик, установщик и сборщик жидкости, торфяная ферма ломают и ставят
 * {@code destroyBlock}/{@code setBlockAndUpdate} без игрока и событий. Вызов заменяется на «не вышло» ({@code false}):
 * ломатель роняет дроп только при удаче, установщик и сборщик тратят предмет и жидкость только после удачной установки
 * (проверено по байткоду). Место это блок машины.
 */
@Pseudo
@Mixin(targets = {
    "com.lothrazar.cyclic.block.breaker.TileBreaker",
    "com.lothrazar.cyclic.block.placer.TilePlacer",
    "com.lothrazar.cyclic.block.placerfluid.TilePlacerFluid",
    "com.lothrazar.cyclic.block.collectfluid.TileFluidCollect",
    "com.lothrazar.cyclic.block.peatfarm.TilePeatFarm"
}, remap = false)
abstract class CyclicMachinesMixin {
    @WrapOperation(method = {"tick", "tryPlacePeat", "tryPlaceWater"}, require = 0, at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/level/Level;destroyBlock(Lnet/minecraft/core/BlockPos;Z)Z"))
    private boolean rysclaims$destroy(Level level, BlockPos pos, boolean drop, Operation<Boolean> original) {
        if (!Machines.blockEntity(this, level, pos)) return false;
        return original.call(level, pos, drop);
    }

    @WrapOperation(method = {"tick", "tryPlacePeat", "tryPlaceWater"}, require = 0, at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/level/Level;setBlockAndUpdate(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;)Z"))
    private boolean rysclaims$set(Level level, BlockPos pos, BlockState state, Operation<Boolean> original) {
        if (!Machines.blockEntity(this, level, pos)) return false;
        return original.call(level, pos, state);
    }
}

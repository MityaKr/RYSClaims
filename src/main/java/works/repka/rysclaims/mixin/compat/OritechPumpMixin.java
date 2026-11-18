package works.repka.rysclaims.mixin.compat;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import works.repka.rysclaims.server.Machines;

/**
 * Насос Oritech 1.2.13: поиск заливкой набирает очередь источников, {@code serverTick} берёт последний, проверяет
 * {@code getBlockState(клетка).getFluidState().isSource()}, затем убирает блок и кладёт ведро в бак. Единственный
 * {@code getBlockState} в {@code serverTick} это та проверка: на земле, закрытой насосу, он отвечает «воздух», насос
 * выкидывает клетку из очереди, бак не пополняется (дюпа нет). Место это блок насоса.
 */
@Pseudo
@Mixin(targets = "rearth.oritech.block.entity.interaction.PumpBlockEntity", remap = false)
abstract class OritechPumpMixin {
    @WrapOperation(method = "serverTick", require = 0, at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/level/Level;getBlockState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;"))
    private BlockState rysclaims$source(Level level, BlockPos pos, Operation<BlockState> original) {
        if (!Machines.blockEntity(this, level, pos)) return Blocks.AIR.defaultBlockState();
        return original.call(level, pos);
    }
}

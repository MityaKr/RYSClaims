package works.repka.rysclaims.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.core.BlockPos;
import net.minecraft.core.FrontAndTop;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.CrafterBlock;
import net.minecraft.world.level.block.entity.CrafterBlockEntity;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import works.repka.rysclaims.server.Borders;

/**
 * Крафтер 1.21 ({@code CrafterBlock.dispenseItem}): кладёт результат в контейнер перед собой ({@code getContainerAt}) или
 * через возможности NeoForge ({@code insertCrafterOutput}). Контейнер на земле другой команды он не видит, предмет
 * выпадает перед ним, как у выбрасывателя (план, раздел 11, строки 26, 28; сверка с FTB 19.11).
 */
@Mixin(CrafterBlock.class)
abstract class CrafterMixin {
    @WrapOperation(method = "dispenseItem", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/level/block/entity/HopperBlockEntity;getContainerAt(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/Container;"))
    private Container rysclaims$container(Level level, BlockPos target, Operation<Container> original, @Local(argsOnly = true) BlockPos pos) {
        if (!Borders.hopper(level, pos, target)) return null;
        return original.call(level, target);
    }

    @WrapOperation(method = "dispenseItem", at = @At(value = "INVOKE",
        target = "Lnet/neoforged/neoforge/items/VanillaInventoryCodeHooks;insertCrafterOutput(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/entity/CrafterBlockEntity;Lnet/minecraft/world/item/ItemStack;)Lnet/minecraft/world/item/ItemStack;"))
    private ItemStack rysclaims$insert(Level level, BlockPos pos, CrafterBlockEntity crafter, ItemStack stack, Operation<ItemStack> original) {
        FrontAndTop o = level.getBlockState(pos).getValue(BlockStateProperties.ORIENTATION);
        if (!Borders.hopper(level, pos, pos.relative(o.front()))) return stack;
        return original.call(level, pos, crafter, stack);
    }
}

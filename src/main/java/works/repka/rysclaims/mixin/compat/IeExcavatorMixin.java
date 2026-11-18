package works.repka.rysclaims.mixin.compat;

import java.lang.reflect.Method;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import works.repka.rysclaims.server.ClaimService;
import works.repka.rysclaims.server.Machines;

/**
 * Экскаватор Immersive Engineering 12.4.2 (R0, раздел 1.2): {@code ExcavatorLogic.digBlock(относительная позиция,
 * IMultiblockLevel)} ломает {@code onDestroyedByPlayer} общим фейковым игроком без {@code BreakEvent}. Отмена в начале:
 * дроп ещё не посчитан, колесо получает пустой стек. Место это начало мультиблока ({@code getAbsoluteOrigin}); тип
 * {@code IMultiblockLevel} берётся отражением, чтобы не тянуть IE в сборку.
 */
@Pseudo
@Mixin(targets = "blusunrize.immersiveengineering.common.blocks.multiblocks.logic.ExcavatorLogic", remap = false)
abstract class IeExcavatorMixin {
    private static Method rysclaims$origin, rysclaims$toAbsolute, rysclaims$level;

    @Inject(method = "digBlock", at = @At("HEAD"), cancellable = true, require = 0)
    private void rysclaims$dig(BlockPos relative, @Coerce Object ctx, CallbackInfoReturnable<ItemStack> cir) {
        try {
            if (rysclaims$origin == null) {
                Class<?> type = Class.forName("blusunrize.immersiveengineering.api.multiblocks.blocks.env.IMultiblockLevel");
                rysclaims$origin = type.getMethod("getAbsoluteOrigin");
                rysclaims$toAbsolute = type.getMethod("toAbsolute", BlockPos.class);
                rysclaims$level = type.getMethod("getRawLevel");
            }
            Level level = (Level) rysclaims$level.invoke(ctx);
            BlockPos origin = (BlockPos) rysclaims$origin.invoke(ctx);
            BlockPos target = (BlockPos) rysclaims$toAbsolute.invoke(ctx, relative);
            if (!Machines.at(level, origin, null, target, "immersiveengineering:excavator", ClaimService.MACHINE_REACH)) {
                cir.setReturnValue(ItemStack.EMPTY);
            }
        } catch (ReflectiveOperationException | ClassCastException ignored) {
            // другая версия IE: экскаватор не трогаем
        }
    }
}

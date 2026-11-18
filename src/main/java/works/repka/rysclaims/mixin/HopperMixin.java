package works.repka.rysclaims.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.entity.Hopper;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import works.repka.rysclaims.server.Borders;

/**
 * Воронка и вагонетка с воронкой (план, раздел 11, строки 26, 27). Перехват в начале метода: NeoForge первым делом зовёт
 * {@code VanillaInventoryCodeHooks.insertHook} и {@code extractHook} (контейнеры модов через возможности), их тоже надо
 * остановить. Воронка не кладёт в контейнер на земле другой команды; тянет она всегда из блока над собой (тот же чанк),
 * поэтому вытягивание судится только у вагонетки: на земле не той команды, где её поставили, она не тянет.
 */
@Mixin(HopperBlockEntity.class)
abstract class HopperMixin {
    @Inject(method = "ejectItems", at = @At("HEAD"), cancellable = true)
    private static void rysclaims$eject(Level level, BlockPos pos, HopperBlockEntity hopper, CallbackInfoReturnable<Boolean> cir) {
        if (level.isClientSide) return;
        var state = hopper.getBlockState();
        if (!state.hasProperty(HopperBlock.FACING)) return;
        if (!Borders.hopper(level, pos, pos.relative(state.getValue(HopperBlock.FACING)))) cir.setReturnValue(false);
    }

    @Inject(method = "suckInItems", at = @At("HEAD"), cancellable = true)
    private static void rysclaims$suck(Level level, Hopper hopper, CallbackInfoReturnable<Boolean> cir) {
        if (!level.isClientSide && hopper instanceof Entity cart && !Borders.minecartHopper(cart)) cir.setReturnValue(false);
    }
}

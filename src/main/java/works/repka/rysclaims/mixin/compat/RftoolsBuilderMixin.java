package works.repka.rysclaims.mixin.compat;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import works.repka.rysclaims.server.Machines;

/**
 * RFTools Builder 7.0.7 (R0, раздел 1.2): постройка, копия, перенос и обмен ставят блоки {@code setBlock} без событий, перенос
 * и обмен ещё и убирают блоки в исходной области. Карьер и очистка зовут свой {@code BreakEvent} с профилем хозяина: их
 * судят события. Отмена в начале метода, пока ничего не потрачено и не сдвинуто; при переносе и обмене проверяются обе
 * точки. Место это блок машины, дальность любая. Постройка в закрытой клетке ждёт ({@code buildBlock} вернул «нет»).
 */
@Pseudo
@Mixin(targets = "mcjty.rftoolsbuilder.modules.builder.blocks.BuilderTileEntity", remap = false)
abstract class RftoolsBuilderMixin {
    private boolean rysclaims$may(Level level, BlockPos pos) {
        Object self = this;
        return !(self instanceof BlockEntity be) || Machines.blockEntity(be, level, pos);
    }

    @Inject(method = "buildBlock(ILnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/block/state/BlockState;)Z",
        at = @At("HEAD"), cancellable = true, require = 0)
    private void rysclaims$build(int rfNeeded, BlockPos pos, BlockState current, BlockState pickState, CallbackInfoReturnable<Boolean> cir) {
        Object self = this;
        if (self instanceof BlockEntity be && !rysclaims$may(be.getLevel(), pos)) cir.setReturnValue(false);
    }

    @Inject(method = "clearBlock(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;)V", at = @At("HEAD"), cancellable = true, require = 0)
    private void rysclaims$clear(Level level, BlockPos pos, CallbackInfo ci) {
        if (!rysclaims$may(level, pos)) ci.cancel();
    }

    @Inject(method = "copyBlock(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;)V",
        at = @At("HEAD"), cancellable = true, require = 0)
    private void rysclaims$copy(Level from, BlockPos src, Level to, BlockPos dest, CallbackInfo ci) {
        if (!rysclaims$may(to, dest)) ci.cancel();
    }

    @Inject(method = "moveBlock", at = @At("HEAD"), cancellable = true, require = 0)
    private void rysclaims$move(Level from, BlockPos src, Level to, BlockPos dest, @Coerce Object rotate, CallbackInfo ci) {
        if (!rysclaims$may(from, src) || !rysclaims$may(to, dest)) ci.cancel();
    }

    @Inject(method = "swapBlock(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;)V",
        at = @At("HEAD"), cancellable = true, require = 0)
    private void rysclaims$swap(Level from, BlockPos src, Level to, BlockPos dest, CallbackInfo ci) {
        if (!rysclaims$may(from, src) || !rysclaims$may(to, dest)) ci.cancel();
    }
}

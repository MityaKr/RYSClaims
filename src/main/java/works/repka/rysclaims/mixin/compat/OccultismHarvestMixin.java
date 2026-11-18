package works.repka.rysclaims.mixin.compat;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import works.repka.rysclaims.server.Machines;

/**
 * Дух-фермер Occultism: {@code tick(дух)} собирает урожай {@code destroyBlock} (дроп падает в мир) и пересаживает
 * {@code setBlock} без событий. Цель берётся из памяти духа внутри метода, поэтому дух запоминается в начале тика, а
 * проверка стоит на самих вызовах. Сервер один поток: хватает поля.
 */
@Pseudo
@Mixin(targets = "com.klikli_dev.occultism.common.entity.ai.behaviour.HarvestCropBehaviour", remap = false)
abstract class OccultismHarvestMixin {
    private static Entity rysclaims$spirit;

    @Inject(method = "tick(Lcom/klikli_dev/occultism/common/entity/spirit/SpiritEntity;)V", at = @At("HEAD"), require = 0)
    private void rysclaims$start(@Coerce Entity spirit, CallbackInfo ci) {
        rysclaims$spirit = spirit;
    }

    @Inject(method = "tick(Lcom/klikli_dev/occultism/common/entity/spirit/SpiritEntity;)V", at = @At("RETURN"), require = 0)
    private void rysclaims$end(@Coerce Entity spirit, CallbackInfo ci) {
        rysclaims$spirit = null;
    }

    @WrapOperation(method = "tick(Lcom/klikli_dev/occultism/common/entity/spirit/SpiritEntity;)V", require = 0,
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;destroyBlock(Lnet/minecraft/core/BlockPos;Z)Z"))
    private boolean rysclaims$harvest(Level level, BlockPos pos, boolean drop, Operation<Boolean> original) {
        if (!Machines.entity(rysclaims$spirit, pos)) return false;
        return original.call(level, pos, drop);
    }

    @WrapOperation(method = "tick(Lcom/klikli_dev/occultism/common/entity/spirit/SpiritEntity;)V", require = 0,
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;I)Z"))
    private boolean rysclaims$replant(Level level, BlockPos pos, BlockState state, int flags, Operation<Boolean> original) {
        if (!Machines.entity(rysclaims$spirit, pos)) return false;
        return original.call(level, pos, state, flags);
    }
}

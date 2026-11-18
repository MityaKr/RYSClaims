package works.repka.rysclaims.mixin.compat;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import works.repka.rysclaims.server.Machines;

/**
 * Дух-лесоруб Occultism 1.224.4 (R0, раздел 1.3): {@code fellTree(дух, корень)} валит дерево {@code destroyBlock} без событий.
 * Дух судится как машина с профилем своего хозяина (прирученный), место это сам дух. Чужое дерево не валится целиком.
 */
@Pseudo
@Mixin(targets = "com.klikli_dev.occultism.common.entity.ai.behaviour.FellTreeBehaviour", remap = false)
abstract class OccultismSpiritsMixin {
    @Inject(method = "fellTree", at = @At("HEAD"), cancellable = true, require = 0)
    private void rysclaims$fell(@Coerce Entity spirit, BlockPos root, CallbackInfo ci) {
        if (!Machines.entity(spirit, root)) ci.cancel();
    }
}

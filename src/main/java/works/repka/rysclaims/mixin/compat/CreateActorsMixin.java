package works.repka.rysclaims.mixin.compat;

import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import works.repka.rysclaims.server.Machines;

/**
 * Комбайн, каток и плуг на постройке Create 6.0.10: {@code visitNewPosition(контекст, позиция)} у них свой.
 * Комбайн срезает урожай {@code destroyBlockAs(.., null, ..)} и пересаживает {@code setBlockAndUpdate}, каток ломает
 * столбцы вокруг и мостит, плуг ломает мягкие блоки и пашет мотыгой от общего фейкового игрока «Plough». Отмена в
 * начале; если можно, на время шага фейковый игрок плуга считается проверенным ({@link Machines#vouch}): его вспашка
 * идёт событиями, а общий профиль иначе отказал бы и на своей земле. Место это якорь постройки.
 */
@Pseudo
@Mixin(targets = {
    "com.simibubi.create.content.contraptions.actors.harvester.HarvesterMovementBehaviour",
    "com.simibubi.create.content.contraptions.actors.roller.RollerMovementBehaviour",
    "com.simibubi.create.content.contraptions.actors.plough.PloughMovementBehaviour"
}, remap = false)
abstract class CreateActorsMixin {
    @Inject(method = "visitNewPosition", at = @At("HEAD"), cancellable = true, require = 0)
    private void rysclaims$visit(@Coerce Object context, BlockPos pos, CallbackInfo ci) {
        if (!Machines.contraption(context, pos)) {
            ci.cancel();
            return;
        }
        Machines.vouch(true);
    }

    @Inject(method = "visitNewPosition", at = @At("RETURN"), require = 0)
    private void rysclaims$done(@Coerce Object context, BlockPos pos, CallbackInfo ci) {
        Machines.vouch(false);
    }
}

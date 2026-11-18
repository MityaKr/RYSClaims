package works.repka.rysclaims.mixin.compat;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import works.repka.rysclaims.server.Machines;

/**
 * Порт пространственного ввода-вывода AE2 19.2.19: {@code SpatialIOPortBlockEntity.transition()} сразу зовёт
 * {@code doSpatialTransition} (CFR, {@code ifPresent} синхронный). На время вызова порт запоминается как работающая машина
 * ({@link Machines#actor}), {@code Ae2SpatialMixin} берёт его место: сеть без хозяина ({@code playerId = -1}) на своей
 * земле больше не судится как «машина снаружи».
 */
@Pseudo
@Mixin(targets = "appeng.blockentity.spatial.SpatialIOPortBlockEntity", remap = false)
abstract class Ae2SpatialPortMixin {
    @Inject(method = "transition()V", at = @At("HEAD"), require = 0)
    private void rysclaims$start(CallbackInfo ci) {
        Machines.actor(this);
    }

    @Inject(method = "transition()V", at = @At("RETURN"), require = 0)
    private void rysclaims$end(CallbackInfo ci) {
        Machines.actor(null);
    }
}

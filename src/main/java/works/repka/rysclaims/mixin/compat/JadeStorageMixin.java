package works.repka.rysclaims.mixin.compat;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import works.repka.rysclaims.compat.jade.JadeHide;

/**
 * Jade 15.10.6: предметы и жидкости в подсказке собирает сервер в {@code putData(Accessor)} (проверено CFR). Где игроку
 * нельзя «Открывать», содержимое не собирается, вместо него метка {@link JadeHide#KEY}.
 */
@Pseudo
@Mixin(targets = {"snownee.jade.addon.universal.ItemStorageProvider", "snownee.jade.addon.universal.FluidStorageProvider"}, remap = false)
abstract class JadeStorageMixin {
    @Inject(method = "putData", at = @At("HEAD"), cancellable = true, require = 0)
    private static void rysclaims$hide(@Coerce Object accessor, CallbackInfo ci) {
        if (JadeHide.hide(accessor)) ci.cancel();
    }
}

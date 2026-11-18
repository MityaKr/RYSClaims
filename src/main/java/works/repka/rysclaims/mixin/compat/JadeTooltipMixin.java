package works.repka.rysclaims.mixin.compat;

import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import works.repka.rysclaims.compat.jade.JadeHide;

/**
 * Клиент Jade 15.10.6: {@code ItemStorageProvider.append(подсказка, аксессор, настройки)} рисует предметы из данных сервера.
 * Если сервер положил метку {@link JadeHide#KEY}, вместо «заперто» Jade пишется «содержимое скрыто: земля …».
 */
@Pseudo
@Mixin(targets = "snownee.jade.addon.universal.ItemStorageProvider", remap = false)
abstract class JadeTooltipMixin {
    @Inject(method = "append", at = @At("HEAD"), cancellable = true, require = 0)
    private static void rysclaims$hidden(@Coerce Object tooltip, @Coerce Object accessor, @Coerce Object config, CallbackInfo ci) {
        Component line = JadeHide.hiddenLine(accessor);
        if (line != null && JadeHide.addLine(tooltip, line)) ci.cancel();
    }
}

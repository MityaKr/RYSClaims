package works.repka.rysclaims.client;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * Что видит игрок при отказе (план, раздел 11, «Что видит игрок при отказе»). В этой части: строка над хотбаром по
 * пакету {@code Denied}; замок у прицела и тост на вход придут с клиентом (З4).
 */
public final class DenyFeedback {
    private DenyFeedback() {}

    public static void show(String reason, String owner) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        mc.gui.setOverlayMessage(text(reason), false);
    }

    public static Component text(String reason) {
        return Component.translatableWithFallback("rysclaims.deny." + reason, "You cannot do this here");
    }
}

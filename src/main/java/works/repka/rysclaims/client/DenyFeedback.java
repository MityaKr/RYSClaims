package works.repka.rysclaims.client;

import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;
import works.repka.ryscore.theme.ThemeManager;
import works.repka.ryscore.ui.Draw;
import works.repka.ryscore.ui.Icons;
import works.repka.ryscore.ui.Notice;
import works.repka.ryscore.ui.Notices;

/**
 * Что видит игрок при отказе (план, раздел 11, «Что видит игрок при отказе», макет {@code 06}): у прицела на секунду замок
 * и короткая причина; тост один раз за заход на чужую землю, при первой попытке, без звука. Причину шлёт сервер пакетом
 * {@code Denied}; гашение нажатия на клиенте до пакета придёт с кэшем прав окна земли.
 */
public final class DenyFeedback {
    /** Сколько держится замок у прицела и сколько он гаснет. */
    private static final long SHOW_MS = 1000, FADE_MS = 250;
    /** Тост той же земли не чаще раза в 5 минут: «один раз за вход» без знания границ на клиенте. */
    private static final long TOAST_MS = 5 * 60_000;
    private static final int FALLBACK_DENY = 0xFFE0564B;

    private static @Nullable Component text;
    private static long shownAt;
    private static final Map<String, Long> TOASTED = new HashMap<>();

    private DenyFeedback() {}

    public static void show(String reason, String owner) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        text = text(reason);
        shownAt = System.currentTimeMillis();
        toast(reason, owner);
    }

    public static Component text(String reason) {
        return Component.translatableWithFallback("rysclaims.deny." + reason, "You cannot do this here");
    }

    private static void toast(String reason, String owner) {
        if ("pvp".equals(reason) || "wilderness".equals(reason)) return;
        String key = owner.isEmpty() ? "?" : owner;
        long now = System.currentTimeMillis();
        Long last = TOASTED.get(key);
        if (last != null && now - last < TOAST_MS) return;
        TOASTED.put(key, now);
        String title = owner.isEmpty()
            ? Component.translatableWithFallback("rysclaims.toast.hidden", "This land is taken").getString()
            : Component.translatableWithFallback("rysclaims.toast.foreign", "Land of %s", owner).getString();
        Notices.show(Notice.card(Notice.CLAIMS, "deny").icon(Icons.Shape.SHIELD).title(title).note(text(reason).getString()).bad());
    }

    /** Слой над прицелом: замок и причина под ним, затухание в конце. Прячется с интерфейсом (F1). */
    public static void render(GuiGraphics g, DeltaTracker delta) {
        Component t = text;
        if (t == null) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.options.hideGui || mc.player == null) return;
        long age = System.currentTimeMillis() - shownAt;
        if (age > SHOW_MS + FADE_MS) {
            text = null;
            return;
        }
        float alpha = age <= SHOW_MS ? 1f : 1f - (age - SHOW_MS) / (float) FADE_MS;
        int a = Math.max(8, (int) (alpha * 255)) << 24;
        int deny = denyColor();
        int cx = g.guiWidth() / 2, cy = g.guiHeight() / 2;
        Font font = mc.font;
        Draw.lock(g, cx - 3, cy + 7, (deny & 0xFFFFFF) | a);
        int w = font.width(t);
        g.drawString(font, t, cx - w / 2, cy + 18, 0xFFFFFF | a, true);
    }

    private static int denyColor() {
        try {
            return ThemeManager.get().c("map.deny");
        } catch (RuntimeException e) {
            return FALLBACK_DENY;
        }
    }

    public static void reset() {
        text = null;
        TOASTED.clear();
    }
}

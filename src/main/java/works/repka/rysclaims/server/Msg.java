package works.repka.rysclaims.server;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/**
 * Тексты команд: ключ перевода и английский запасной текст (его видит консоль сервера, где переводов нет). Те же строки
 * лежат в {@code lang/en_us.json}, русские в {@code ru_ru.json}. Тексты черновые, автор смотрит их до выпуска.
 */
final class Msg {
    private Msg() {}

    static MutableComponent t(String key, String fallback, Object... args) {
        return Component.translatableWithFallback("rysclaims." + key, fallback, args);
    }

    static MutableComponent yesNo(boolean yes) {
        return yes ? t("yes", "yes") : t("no", "no");
    }
}

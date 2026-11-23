package works.repka.rysclaims.migrate;

import net.minecraft.network.chat.Component;

/**
 * Text of migration report lines. The core's {@code Migrations.Report} carries plain strings, so the text is resolved
 * when the line is made: the translation if the language is loaded (singleplayer), otherwise the English fallback
 * (dedicated server). The same strings are in {@code lang/en_us.json} and {@code ru_ru.json} as
 * {@code rysclaims.migrate.*}.
 */
final class Say {
    private Say() {}

    static String t(String key, String fallback, Object... args) {
        Object[] safe = new Object[args.length];
        for (int i = 0; i < args.length; i++) safe[i] = args[i] instanceof Number ? args[i] : String.valueOf(args[i]);
        return Component.translatableWithFallback("rysclaims.migrate." + key, fallback, safe).getString();
    }
}

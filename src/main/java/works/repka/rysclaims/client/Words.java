package works.repka.rysclaims.client;

import net.minecraft.network.chat.Component;
import works.repka.rysclaims.net.ClaimsNet;
import works.repka.rysclaims.server.ClaimService;

/** Слова окна земли: формы по числу, итог правки, названия ролей. */
public final class Words {
    private Words() {}

    public static String t(String key, Object... args) {
        return Component.translatable("rysclaims." + key, args).getString();
    }

    /** Форма слова по числу: {@code base.one}, {@code base.few}, {@code base.many} (1 чанк, 2 чанка, 5 чанков). */
    public static String plural(long n, String base) {
        long a = Math.abs(n) % 100, b = a % 10;
        String form = a >= 11 && a <= 14 ? "many" : b == 1 ? "one" : b >= 2 && b <= 4 ? "few" : "many";
        return t(base + "." + form, n);
    }

    public static String chunks(long n) {
        return plural(n, "chunks");
    }

    public static String role(String role) {
        return t("role." + role);
    }

    /** Итог правки одной строкой: «Занято 12. Пропущено 3: чужая земля 3». */
    public static String result(ClaimsNet.EditResult r) {
        StringBuilder line = new StringBuilder(switch (r.op()) {
            case ClaimsNet.OP_CLAIM -> t("result.claim", r.done());
            case ClaimsNet.OP_RELEASE -> t("result.release", r.done());
            case ClaimsNet.OP_LOAD -> t("result.load", r.done());
            case ClaimsNet.OP_UNLOAD -> t("result.unload", r.done());
            case ClaimsNet.OP_KEEP -> t("result.keep", r.done());
            case ClaimsNet.OP_LAND + ClaimsNet.LAND_TRUST -> t(r.done() > 0 ? "result.trust" : "result.failed");
            case ClaimsNet.OP_LAND + ClaimsNet.LAND_UNTRUST -> t(r.done() > 0 ? "result.untrust" : "result.failed");
            case ClaimsNet.OP_LAND + ClaimsNet.LAND_FLAG -> t(r.done() > 0 ? "result.flag" : "result.failed");
            case ClaimsNet.OP_LAND + ClaimsNet.LAND_VISIBILITY -> t(r.done() > 0 ? "result.visibility" : "result.failed");
            case ClaimsNet.OP_LAND + ClaimsNet.LAND_RENAME -> t(r.done() > 0 ? "result.rename" : "result.failed");
            case ClaimsNet.OP_LAND + ClaimsNet.LAND_UNLOAD_PLOT -> t("result.unload", r.done());
            case ClaimsNet.OP_LAND + ClaimsNet.LAND_RELEASE_PLOT -> t("result.release", r.done());
            default -> t("result.rights");
        });
        int total = 0;
        StringBuilder why = new StringBuilder();
        ClaimService.Skip[] skips = ClaimService.Skip.values();
        for (int i = 0; i < r.skipped().length && i < skips.length; i++) {
            int n = r.skipped()[i];
            if (n <= 0) continue;
            total += n;
            if (why.length() > 0) why.append(", ");
            why.append(t("skip." + skips[i].name().toLowerCase(java.util.Locale.ROOT))).append(' ').append(n);
        }
        if (total > 0) line.append(' ').append(t("result.skipped", total, why));
        return line.toString();
    }

    /** Была ли правка без пропусков. */
    public static boolean clean(ClaimsNet.EditResult r) {
        for (int n : r.skipped()) if (n > 0) return false;
        return true;
    }
}

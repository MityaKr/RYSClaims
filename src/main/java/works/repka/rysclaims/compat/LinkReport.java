package works.repka.rysclaims.compat;

import java.util.Map;
import java.util.function.Supplier;
import net.neoforged.fml.ModList;
import works.repka.rysclaims.RYSClaims;
import works.repka.rysclaims.compat.sable.SableLand;

/** Строка {@code [rysclaims] связки: ...} при запуске сервера (один раз за запуск игры: применённые перехваты не меняются). */
public final class LinkReport {
    private static volatile String last;

    private LinkReport() {}

    public static void log() {
        if (last == null) {
            Map<Links.Link, Supplier<String>> extra = Map.of(
                Links.Link.BOTANIA, LinkReport::botania,
                Links.Link.SABLE, () -> SableLand.available() ? null : "Sable companion not found");
            last = Links.line(Links.check(id -> ModList.get().isLoaded(id), RYSClaims.class.getClassLoader(), extra));
        }
        RYSClaims.LOG.info(last);
        if (RYSClaims.LOG.isDebugEnabled()) RYSClaims.LOG.debug("[rysclaims] links, handlers without a call site: {}", Links.missing());
    }

    /** Вспышка маны Botania судится по {@code getBurstSourceBlockPos()} (Botania Revived и старая Botania). */
    private static String botania() {
        for (String name : new String[] {"velrondevs.botania.common.entity.ManaBurstEntity", "vazkii.botania.common.entity.ManaBurstEntity"}) {
            try {
                Class.forName(name, false, RYSClaims.class.getClassLoader()).getMethod("getBurstSourceBlockPos");
                return null;
            } catch (ClassNotFoundException | NoSuchMethodException | LinkageError e) {
                // следующее имя
            }
        }
        return "target not found";
    }
}

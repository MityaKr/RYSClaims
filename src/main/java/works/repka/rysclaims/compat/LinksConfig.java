package works.repka.rysclaims.compat;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Файл {@code config/rysclaims-common.toml} с выключателями связок: NeoForge создаёт его с комментариями при первом
 * запуске. Читает значения не он, а {@link Links} (до загрузки игры, построчно): правка вступает в силу после перезапуска.
 */
public final class LinksConfig {
    public static final ModConfigSpec SPEC;

    static {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();
        b.comment("Protection hooks into other mods. false turns a hook off completely (its mixin is not applied),",
            "for example when a mod update broke it. Restart the game after a change.").push(Links.SECTION);
        for (Links.Link l : Links.Link.values()) {
            b.comment(l.title + " (mod id " + l.modId + ")").define(l.key, true);
        }
        b.pop();
        SPEC = b.build();
    }

    private LinksConfig() {}
}

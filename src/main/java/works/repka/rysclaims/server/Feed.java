package works.repka.rysclaims.server;

import java.util.List;
import java.util.UUID;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import works.repka.ryscore.team.TeamsApi;
import works.repka.rysclaims.RYSClaims;
import works.repka.rysclaims.data.Plots;

/** Записи в ленту команды RYSTeams ({@code TeamsApi.post}, план, раздел 8, строка «Лента»). Без RYSTeams ленты нет. */
public final class Feed {
    public static final ResourceLocation SOURCE = ResourceLocation.fromNamespaceAndPath(RYSClaims.MODID, "land");

    private Feed() {}

    static void post(UUID team, Component text, boolean alert) {
        try {
            TeamsApi.get().post(team, new TeamsApi.Post(SOURCE, text, null, alert));
        } catch (RuntimeException e) {
            RYSClaims.LOG.debug("RYSClaims: запись в ленту не ушла: {}", e.toString());
        }
    }

    static Component plotName(int id, String name) {
        return name.isEmpty() ? Component.translatableWithFallback("rysclaims.plot.unnamed", "Plot %s", id) : Component.literal(name);
    }

    /** Слияние и разрез участков: «Участки соединились: Шахта теперь часть Базы», «Участок разделён: База и База 2». */
    static void plots(UUID team, List<Plots.Event> events) {
        for (Plots.Event e : events) {
            Component kept = plotName(e.keptId(), e.kept()), other = plotName(e.otherId(), e.other());
            post(team, e.kind() == Plots.Event.Kind.MERGE
                ? Component.translatableWithFallback("rysclaims.feed.merge", "Plots joined: %s is now part of %s", other, kept)
                : Component.translatableWithFallback("rysclaims.feed.split", "Plot split: %s and %s", kept, other), false);
        }
    }
}

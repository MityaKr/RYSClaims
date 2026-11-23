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

    private record Edit(UUID team, UUID actor, String op) {}

    private static final java.util.Map<Edit, int[]> EDITS = new java.util.LinkedHashMap<>();

    /** Правка земли игроком: копится и уходит в ленту раз в 10 секунд одной строкой на игрока и действие. */
    static void edited(UUID actor, UUID team, String op, int count) {
        if (EDITS.size() < 1024) EDITS.computeIfAbsent(new Edit(team, actor, op), k -> new int[1])[0] += count;
    }

    /** «Lena_K: занято 12 чанков» (план, раздел 8, строка «Лента»). */
    static void flush(net.minecraft.server.MinecraftServer server) {
        if (EDITS.isEmpty()) return;
        EDITS.forEach((e, n) -> post(e.team, Component.translatableWithFallback("rysclaims.feed." + e.op, "%s: %s chunks", Commands.playerName(server, e.actor), n[0]), false));
        EDITS.clear();
    }

    static void clear() {
        EDITS.clear();
    }

    static void post(UUID team, Component text, boolean alert) {
        try {
            TeamsApi.get().post(team, new TeamsApi.Post(SOURCE, text, null, alert));
        } catch (RuntimeException e) {
            RYSClaims.LOG.debug("RYSClaims: feed entry not sent: {}", e.toString());
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

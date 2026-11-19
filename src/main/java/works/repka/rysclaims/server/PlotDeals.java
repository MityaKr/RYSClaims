package works.repka.rysclaims.server;

import it.unimi.dsi.fastutil.longs.LongList;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import works.repka.rysclaims.net.ClaimsNet;

/**
 * Передача и аренда участка другой команде (план, раздел 6 «Потом»): предложение живёт 5 минут, получатель отвечает
 * кнопками уведомления или {@code /claims plot accept|decline}. Принять может тот, у кого в команде-получателе есть право
 * занимать землю; чанки и лимит проверяются заново в момент ответа ({@link ClaimService#handOver}). Одно предложение на
 * команду-получателя: новое заменяет старое.
 */
public final class PlotDeals {
    public static final int GIVE = 2, LEND = 3;
    private static final long OFFER_MS = 5 * 60_000L;

    record Offer(UUID from, UUID to, String dim, LongList chunks, String name, int days, long at, UUID by) {}

    private static final Map<UUID, Offer> OFFERS = new HashMap<>();

    private PlotDeals() {}

    /** Предложение: участок команды {@code from} команде {@code to} насовсем ({@code days} 0) или в аренду. */
    static void offer(ClaimService s, ServerPlayer by, UUID from, UUID to, String dim, LongList chunks, String name, int days) {
        OFFERS.put(to, new Offer(from, to, dim, chunks, name, days, System.currentTimeMillis(), by.getUUID()));
        MinecraftServer server = ClaimsServer.server();
        if (server == null) return;
        boolean fits = s.data().claimed(to) + chunks.size() <= s.limits(to).claims;
        String fromName = Commands.teamName(s, from).getString();
        for (UUID m : s.teams().members(to)) {
            ServerPlayer p = server.getPlayerList().getPlayer(m);
            if (p == null || !s.manages(m, to, ClaimService.PERM_CLAIM)) continue;
            if (ClaimsNet.accepts(p)) {
                ClaimsNet.send(p, new ClaimsNet.Question(days > 0 ? LEND : GIVE, chunks.size(), fits, fromName, name, days));
            } else {
                p.sendSystemMessage(days > 0
                    ? Msg.t("plot.offer.lend", "%s offers you the plot %s (%s chunks) for %s days: /claims plot accept or decline", fromName, name, chunks.size(), days)
                    : Msg.t("plot.offer.give", "%s gives you the plot %s (%s chunks): /claims plot accept or decline", fromName, name, chunks.size()));
            }
        }
    }

    /** Ответ получателя. Возвращает строку итога для игрока. */
    public static Component answer(ServerPlayer player, boolean yes) {
        if (!ClaimsServer.running()) return Msg.t("plot.none", "No plot offer");
        ClaimService s = ClaimsServer.service();
        UUID team = s.teams().teamOf(player.getUUID());
        Offer o = OFFERS.get(team);
        if (o == null || System.currentTimeMillis() - o.at > OFFER_MS) {
            OFFERS.remove(team);
            return Msg.t("plot.none", "No plot offer");
        }
        if (!s.manages(player.getUUID(), team, ClaimService.PERM_CLAIM)) return Msg.t("error.no_right", "Your role in the team cannot do this");
        OFFERS.remove(team);
        if (!yes) {
            tell(o.by, Msg.t("plot.declined", "%s declined the plot %s", Commands.teamName(s, team), o.name));
            return Msg.t("plot.declined.you", "Declined");
        }
        long until = o.days > 0 ? System.currentTimeMillis() + o.days * 86_400_000L : 0;
        int n = s.handOver(o.from, o.to, o.dim, o.chunks, until, o.name);
        if (n < 0) return Msg.t("plot.no_room", "Your team has no room for %s more chunks", o.chunks.size());
        if (n == 0) return Msg.t("plot.gone", "The plot is no longer available");
        tell(o.by, Msg.t("plot.accepted", "%s accepted the plot %s: %s chunks", Commands.teamName(s, team), o.name, n));
        return Msg.t("plot.got", "Plot received: %s chunks", n);
    }

    private static void tell(UUID player, Component text) {
        MinecraftServer server = ClaimsServer.server();
        ServerPlayer p = server == null ? null : server.getPlayerList().getPlayer(player);
        if (p != null) p.sendSystemMessage(text);
    }

    /** Строка ленты обеим командам. */
    static void feed(ClaimService s, UUID from, UUID to, String name, int count, int days) {
        Component fromName = Commands.teamName(s, from), toName = Commands.teamName(s, to);
        if (days < 0) {
            Feed.post(to, Component.translatableWithFallback("rysclaims.feed.lease.back", "Lease over: plot %s (%s chunks) returned from %s", name, count, fromName), false);
            Feed.post(from, Component.translatableWithFallback("rysclaims.feed.lease.gone", "Lease over: plot %s (%s chunks) returned to %s", name, count, toName), false);
        } else if (days == 0) {
            Feed.post(from, Component.translatableWithFallback("rysclaims.feed.plot.given", "Plot %s (%s chunks) given to %s", name, count, toName), true);
            Feed.post(to, Component.translatableWithFallback("rysclaims.feed.plot.got", "Plot %s (%s chunks) received from %s", name, count, fromName), false);
        } else {
            Feed.post(from, Component.translatableWithFallback("rysclaims.feed.plot.lent", "Plot %s (%s chunks) leased to %s for %s days", name, count, toName, days), true);
            Feed.post(to, Component.translatableWithFallback("rysclaims.feed.plot.rented", "Plot %s (%s chunks) rented from %s for %s days", name, count, fromName, days), false);
        }
    }

    public static void clear() {
        OFFERS.clear();
    }
}

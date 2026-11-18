package works.repka.rysclaims.server;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import works.repka.rysclaims.data.Chunks;
import works.repka.rysclaims.data.Plot;
import works.repka.rysclaims.data.TeamLand;
import works.repka.rysclaims.net.ClaimsNet;

/**
 * Хозяевам о попытках чужих (план, макеты {@code 06b}, {@code 43}): тост участникам команды в сети, если команда его
 * включила (не чаще раза в 5 минут на одного чужого), и строка в ленте команды раз в минуту, повторы склеены
 * («Попытка открыть сундук на участке Склад · Grib_ok ×3»), не больше трёх строк на команду за раз. Машины сюда не идут:
 * они повторяют попытку каждый тик, их видно в журнале.
 */
public final class Alerts {
    private static final long TOAST_MS = 5 * 60_000;
    private static final int FEED_PER_FLUSH = 3;

    private record Key(UUID team, UUID actor, String name, ClaimService.Reason reason, int plot) {}

    private static final Map<Key, int[]> FEED = new LinkedHashMap<>();
    private static final Map<String, Long> TOASTED = new HashMap<>();

    private Alerts() {}

    /** Живой игрок получил отказ на земле команды {@code owner}. */
    static void denied(ClaimService s, UUID owner, UUID actor, String name, ClaimService.Reason reason, String dim, BlockPos pos) {
        TeamLand land = s.data().land(owner);
        if (land == null || s.teams().members(owner).contains(actor)) return;
        Plot plot = s.plotAt(dim, Chunks.pack(pos.getX() >> 4, pos.getZ() >> 4));
        if (land.logFeed && FEED.size() < 1024) FEED.computeIfAbsent(new Key(owner, actor, name, reason, plot == null ? 0 : plot.id), k -> new int[1])[0]++;
        if (!land.logToast) return;
        long now = System.currentTimeMillis();
        String key = owner + "/" + actor;
        Long last = TOASTED.get(key);
        if (last != null && now - last < TOAST_MS) return;
        TOASTED.put(key, now);
        MinecraftServer server = ClaimsServer.server();
        if (server == null) return;
        String plotName = plot == null ? "" : plot.name.isEmpty() ? "#" + plot.id : plot.name;
        for (UUID member : s.teams().members(owner)) {
            ServerPlayer p = server.getPlayerList().getPlayer(member);
            if (p != null) ClaimsNet.send(p, new ClaimsNet.OwnerAlert(name, reason.key(), plotName));
        }
    }

    /** Раз в минуту: накопленное в ленту команд. */
    static void flush(ClaimService s) {
        if (FEED.isEmpty()) return;
        Map<UUID, List<Map.Entry<Key, int[]>>> byTeam = new LinkedHashMap<>();
        for (Map.Entry<Key, int[]> e : FEED.entrySet()) byTeam.computeIfAbsent(e.getKey().team, t -> new ArrayList<>()).add(e);
        FEED.clear();
        byTeam.forEach((team, list) -> {
            list.sort((a, b) -> Integer.compare(b.getValue()[0], a.getValue()[0]));
            for (int i = 0; i < list.size() && i < FEED_PER_FLUSH; i++) {
                Key k = list.get(i).getKey();
                int n = list.get(i).getValue()[0];
                Plot plot = k.plot == 0 ? null : s.plot(team, k.plot);
                MutableComponent line = plot == null
                    ? Component.translatableWithFallback("rysclaims.feed.deny." + k.reason.key(), "Attempt: %s", k.name)
                    : Component.translatableWithFallback("rysclaims.feed.deny_at." + k.reason.key(), "Attempt on %s: %s", Feed.plotName(plot.id, plot.name), k.name);
                if (n > 1) line.append(" ×" + n);
                Feed.post(team, line, false);
            }
            if (list.size() > FEED_PER_FLUSH) {
                Feed.post(team, Component.translatableWithFallback("rysclaims.feed.deny_more", "and %s more attempts, see the log", list.size() - FEED_PER_FLUSH), false);
            }
        });
        if (TOASTED.size() > 4096) TOASTED.clear();
    }

    static void clear() {
        FEED.clear();
        TOASTED.clear();
    }
}

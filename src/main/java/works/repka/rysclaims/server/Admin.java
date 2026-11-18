package works.repka.rysclaims.server;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongList;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import works.repka.ryscore.team.TeamsApi;
import works.repka.rysclaims.data.Chunks;
import works.repka.rysclaims.data.Limits;
import works.repka.rysclaims.data.Plots;
import works.repka.rysclaims.data.TeamLand;
import works.repka.rysclaims.net.ClaimsNet;

/**
 * Оператор в окне земли (план, макеты {@code 51}–{@code 53}, этап З12): настройки сервера, таблица команд с добавками к
 * лимитам, режим оператора, перенос из FTB кнопкой. Всё требует уровня оператора 2 (перенос 4, как команда), сервер
 * проверяет заново. В режиме оператора правка чужой земли из окна идёт от имени её хозяев, пишется в их журнал и ленту.
 */
public final class Admin {
    private Admin() {}

    public static void edit(ServerPlayer player, ClaimsNet.AdminEdit e) {
        if (!ClaimsServer.running() || !player.hasPermissions(2)) return;
        ClaimService s = ClaimsServer.service();
        MinecraftServer server = player.server;
        switch (e.kind()) {
            case "setting" -> {
                if (ServerConfig.set(e.key(), e.value())) {
                    ClaimsServer.settingsChanged();
                    works.repka.rysclaims.RYSClaims.LOG.info("RYSClaims: {} поменял настройку {} = {}", player.getGameProfile().getName(), e.key(), e.value());
                } else {
                    player.sendSystemMessage(Msg.t("admin.bad_value", "The server did not accept %s = %s", e.key(), e.value()));
                }
            }
            case "bypass" -> {
                boolean on = s.toggleBypass(player.getUUID());
                player.sendSystemMessage(on ? Msg.t("bypass.on", "Operator mode: protection off for you") : Msg.t("bypass.off", "Operator mode off"));
            }
            case "limit" -> {
                UUID team;
                try {
                    team = UUID.fromString(e.key());
                } catch (IllegalArgumentException ex) {
                    return;
                }
                String[] parts = e.value().split(",");
                if (parts.length != 2) return;
                try {
                    TeamLand land = s.data().landOrCreate(team);
                    int claims = Integer.parseInt(parts[0].trim()), force = Integer.parseInt(parts[1].trim());
                    if (Math.abs(claims) > 1_000_000 || Math.abs(force) > 1_000_000) return;
                    s.addBonus(team, claims - land.bonusClaims, force - land.bonusForce);
                    ClaimSync.touch(team);
                } catch (NumberFormatException ignored) {
                    return;
                }
            }
            case "import", "undo" -> {
                if (!player.hasPermissions(4)) return;
                server.getCommands().performPrefixedCommand(player.createCommandSourceStack(), "claims admin import " + ("import".equals(e.kind()) ? "ftb" : "undo"));
            }
            case "refresh" -> {
                // только прислать состояние
            }
            default -> {
                return;
            }
        }
        ClaimsNet.send(player, state(s, server, player));
    }

    /** Состояние для окна оператора: настройки, команды, режим оператора. */
    static ClaimsNet.AdminState state(ClaimService s, MinecraftServer server, ServerPlayer player) {
        Map<String, String> values = ServerConfig.values();
        List<ClaimsNet.TeamRow> rows = new ArrayList<>();
        Set<UUID> ids = new LinkedHashSet<>();
        for (Map.Entry<UUID, TeamLand> e : s.data().lands().entrySet()) if (e.getValue().claimed() > 0 || e.getValue().legacyName != null) ids.add(e.getKey());
        try {
            for (UUID t : TeamsApi.get().teams()) if (!TeamsApi.get().isSolo(t) || s.data().claimed(t) > 0) ids.add(t);
        } catch (RuntimeException ignored) {
            // без RYSTeams команд нет, только земля
        }
        for (UUID team : ids) {
            TeamLand land = s.data().land(team);
            Limits lim = s.limits(team);
            boolean online = false;
            for (UUID m : s.teams().members(team)) if (server.getPlayerList().getPlayer(m) != null) online = true;
            String note = land != null && "ftb-server".equals(land.legacySource) ? "server" : land != null && land.legacyName != null ? "ftb" : "";
            int members = land != null && land.legacyName != null ? land.legacyMembers.size() : s.teams().members(team).size();
            // «На карте»: середина самого большого участка
            String dim = "";
            int x = 0, z = 0, best = 0;
            for (String d : s.data().dimensions()) {
                for (LongList g : Plots.groups(s.data(), team, d)) {
                    if (g.size() <= best) continue;
                    best = g.size();
                    long c = Plots.center(g);
                    dim = d;
                    x = Chunks.x(c) * 16 + 8;
                    z = Chunks.z(c) * 16 + 8;
                }
            }
            int color = 0xFF000000 | TeamsApi.get().color(team);
            rows.add(new ClaimsNet.TeamRow(team, Commands.teamName(s, team).getString(), color, members, s.data().claimed(team), lim.claims,
                s.data().forced(team), lim.force, land == null ? 0 : land.lastSeen, online, note, land == null ? 0 : land.bonusClaims,
                land == null ? 0 : land.bonusForce, dim, x, z));
        }
        rows.sort((a, b) -> Integer.compare(b.claimed(), a.claimed()));
        return new ClaimsNet.AdminState(new ArrayList<>(values.keySet()), new ArrayList<>(values.values()), rows, s.bypass(player.getUUID()),
            s.data().total(), player.hasPermissions(4));
    }

    /**
     * Режим оператора, правка чужой земли из окна (план, макет 53): освободить, прогрузить, выключить прогрузку чанки любых
     * команд от имени их хозяев, мимо прав и лимитов. Каждой команде строка в журнал и ленту.
     */
    static ClaimService.Result foreignEdit(ClaimService s, ServerPlayer player, UUID own, String dim, int op, LongArrayList chunks) {
        Map<UUID, LongList> byOwner = new LinkedHashMap<>();
        ClaimService.Result total = new ClaimService.Result();
        for (long c : chunks) {
            UUID owner = s.data().owner(dim, c);
            if (owner == null) {
                total.skipped.merge(ClaimService.Skip.NOT_CLAIMED, 1, Integer::sum);
                continue;
            }
            byOwner.computeIfAbsent(owner, k -> new LongArrayList()).add(c);
        }
        String name = player.getGameProfile().getName();
        byOwner.forEach((owner, list) -> {
            boolean admin = !owner.equals(own);
            ClaimService.Result r = switch (op) {
                case ClaimsNet.OP_RELEASE -> s.release(player.getUUID(), owner, dim, list, admin);
                case ClaimsNet.OP_LOAD -> s.load(player.getUUID(), owner, dim, list, true, admin);
                case ClaimsNet.OP_UNLOAD -> s.load(player.getUUID(), owner, dim, list, false, admin);
                default -> new ClaimService.Result();
            };
            total.done += r.done;
            r.skipped.forEach((k, n) -> total.skipped.merge(k, n, Integer::sum));
            if (admin && r.done > 0) {
                String key = op == ClaimsNet.OP_RELEASE ? "release" : op == ClaimsNet.OP_LOAD ? "load" : "unload";
                Feed.post(owner, Component.translatableWithFallback("rysclaims.feed.operator." + key, "Operator %s: %s chunks", name, r.done), true);
                DenyLog log = ClaimsServer.log();
                long first = list.getLong(0);
                if (log != null) log.add(owner, player.getUUID(), name, false, ClaimService.Reason.OPERATOR, dim, Chunks.x(first) * 16 + 8, player.getBlockY(), Chunks.z(first) * 16 + 8);
                ClaimSync.touch(owner);
            }
        });
        return total;
    }
}

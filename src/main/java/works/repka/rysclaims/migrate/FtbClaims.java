package works.repka.rysclaims.migrate;

import com.google.gson.JsonElement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;
import works.repka.ryscore.migrate.Migrations;
import works.repka.ryscore.migrate.Snbt;
import works.repka.rysclaims.data.Chunks;
import works.repka.rysclaims.data.Claim;
import works.repka.rysclaims.data.ClaimData;
import works.repka.rysclaims.data.Limits;
import works.repka.rysclaims.data.Rights;
import works.repka.rysclaims.data.Settings;
import works.repka.rysclaims.data.TeamLand;
import works.repka.rysclaims.server.Teams;

/**
 * Перенос земли FTB Chunks в RYSClaims без игры (план, раздел 10.5): считает новую базу целиком в памяти, ничего не пишет.
 * Шаг {@link ClaimsStep} потом кладёт её одним сохранением и ставит билеты.
 *
 * <p>Правило: перенос никогда не снимает включённую прогрузку, не оставляет команду сверх лимита и не отнимает доступ у
 * игрока, у которого он был. Код FTB не используется: читаются только файлы ({@link FtbWorld}).
 */
public final class FtbClaims {
    /** Что получилось: новая база, настройки FTB в наших, отчёт. */
    public static final class Plan {
        public final ClaimData data;
        /** Настройки сервера, собранные из FTB поверх {@code base}. */
        public final Settings settings;
        public final Migrations.Report report = Migrations.Report.create();
        /** Расхождения «было в FTB, стало»: строки {@code MISMATCH}. */
        public final List<String> mismatches = new ArrayList<>();
        /** Куда ушла земля каждой команды FTB. */
        public final Map<UUID, UUID> target = new LinkedHashMap<>();
        /** Имена для отчёта: команда RYS → имя её первой команды FTB. */
        final Map<UUID, String> names = new LinkedHashMap<>();

        Plan(ClaimData data, Settings settings) {
            this.data = data;
            this.settings = settings;
        }
    }

    private FtbClaims() {}

    /**
     * @param base настройки, от которых считаются лимиты: FTB поверх них, если {@code applySettings}
     * @param applySettings переносить ли настройки FTB (только если наш файл настроек ещё не трогали)
     * @param teams команды RYSTeams (или соло)
     * @param known команды, которые есть в RYSTeams ({@code TeamsApi.teams()})
     */
    public static Plan plan(FtbWorld w, Settings base, boolean applySettings, Teams teams, Collection<UUID> known) {
        Settings s = applySettings ? FtbSettings.apply(w.config, copy(base)) : base;
        ClaimData data = new ClaimData(s.defaultRights);
        Plan plan = new Plan(data, s);
        Migrations.Report r = plan.report;
        Set<UUID> knownSet = new HashSet<>(known);
        for (String e : w.errors) r.partial("файл не прочитался: " + e);

        // личные добавки: в личных файлах игроков
        for (FtbWorld.Land land : w.lands.values()) {
            if (land.extraClaims == 0 && land.extraForce == 0) continue;
            var b = data.bonusOrCreate(land.id);
            b.claims = land.extraClaims;
            b.force = land.extraForce;
            r.count("bonuses", 1);
        }

        Set<UUID> configured = new HashSet<>();
        for (FtbWorld.Land land : w.lands.values()) {
            if (land.chunkCount() == 0) continue;
            FtbWorld.Team team = w.teams.get(land.id);
            UUID to = target(plan, team, land.id, teams, knownSet);
            plan.target.put(land.id, to);
            plan.names.putIfAbsent(to, name(team, land.id));
            TeamLand dst = data.landOrCreate(to);
            if (configured.add(to)) {
                if (team != null) properties(team, dst);
            } else {
                r.line("земля команды FTB " + name(team, land.id) + " добавлена к уже перенесённой; права взяты у первой");
            }
            // союзники FTB это игроки: становятся доверенными, доступ не теряется
            if (team != null) {
                for (UUID ally : team.allies()) {
                    if (dst.trusted.add(ally)) r.count("trusted", 1);
                    if (!teams.teamOf(ally).equals(ally)) {
                        r.line("доверенный " + ally + " состоит в команде RYSTeams: можно заменить союзом команд");
                    }
                }
            }
            int expiring = 0;
            for (Map.Entry<String, List<FtbWorld.Chunk>> e : land.chunks.entrySet()) {
                String dim = e.getKey();
                if (!s.claimable(dim)) r.line(name(team, land.id) + ": земля в " + dim + " перенесена, хотя там приват закрыт: её не отнимаем");
                for (FtbWorld.Chunk c : e.getValue()) {
                    long pos = Chunks.pack(c.x(), c.z());
                    Claim claim = data.put(dim, pos, to, null, c.time());
                    if (claim == null) {
                        UUID owner = data.owner(dim, pos);
                        plan.mismatches.add("MISMATCH " + dim + " " + Chunks.text(pos) + ": занят дважды (" + land.id + " и " + owner + ")");
                        continue;
                    }
                    if (c.forceLoaded() != 0) data.setForce(claim, true);
                    if (c.expiry() != 0) expiring++;
                }
            }
            if (expiring > 0) r.line(name(team, land.id) + ": у " + expiring + " чанков был срок FTB; срок теперь задаёт режим сервера");
            if (land.preventedAccess > 0) r.line(name(team, land.id) + ": записей журнала FTB " + land.preventedAccess + " не перенесено (журнал придёт в 0.2)");
        }

        // лимиты: не меньше действующих в FTB и не меньше занятого
        Map<UUID, int[]> ftb = new LinkedHashMap<>();
        for (Map.Entry<UUID, UUID> e : plan.target.entrySet()) {
            FtbWorld.Land land = w.lands.get(e.getKey());
            int[] sum = ftb.computeIfAbsent(e.getValue(), t -> new int[4]);
            sum[0] += land.chunkCount();
            sum[1] += land.forcedCount();
            sum[2] = Math.max(sum[2], land.maxClaims);
            sum[3] = Math.max(sum[3], land.maxForce);
        }
        for (Map.Entry<UUID, int[]> e : ftb.entrySet()) {
            UUID team = e.getKey();
            int[] f = e.getValue();
            TeamLand land = data.landOrCreate(team);
            Limits lim = Limits.of(s, land, members(teams, team), teams.leader(team), data::bonus);
            int needClaims = Math.max(f[0], f[2]), needForce = Math.max(f[1], f[3]);
            if (lim.claims < needClaims) {
                land.bonusClaims += needClaims - lim.claims;
                r.line(label(plan, team) + ": добавка команде +" + (needClaims - lim.claims) + " чанков, чтобы лимит был не меньше FTB");
            }
            if (lim.force < needForce) {
                land.bonusForce += needForce - lim.force;
                r.line(label(plan, team) + ": добавка команде +" + (needForce - lim.force) + " прогрузки, чтобы лимит был не меньше FTB");
            }
            Limits after = Limits.of(s, land, members(teams, team), teams.leader(team), data::bonus);
            int claimed = data.claimed(team), forced = data.forced(team);
            r.line(label(plan, team) + ": чанков " + f[0] + " → " + claimed + ", прогружено " + f[1] + " → " + forced
                + ", лимиты FTB " + f[2] + "/" + f[3] + " → " + after.claims + "/" + after.force);
            if (claimed != f[0] || forced != f[1]) {
                plan.mismatches.add("MISMATCH " + label(plan, team) + ": чанков FTB " + f[0] + ", стало " + claimed + "; прогружено FTB " + f[1] + ", стало " + forced);
            }
            r.count("teams", 1);
        }
        r.count("chunks", data.total());
        int forced = 0;
        for (TeamLand l : data.lands().values()) forced += l.forced();
        r.count("forced", forced);
        for (String m : plan.mismatches) r.line(m);
        return plan;
    }

    /** Куда уходит земля команды FTB. */
    private static UUID target(Plan plan, @Nullable FtbWorld.Team team, UUID id, Teams teams, Set<UUID> known) {
        Migrations.Report r = plan.report;
        if (team == null) {
            legacy(plan.data, id, id.toString().substring(0, 8), "ftb");
            r.skipped("земля " + id, "нет файла команды FTB, осталась за старым UUID");
            return id;
        }
        switch (team.type) {
            case "player" -> {
                return id; // личная команда игрока: личная земля
            }
            case "server" -> {
                legacy(plan.data, id, team.name, "ftb-server");
                r.line("земля серверной команды FTB " + team.name + " осталась за ней (" + id + ")");
                return id;
            }
            default -> {
                if (known.contains(id)) return id; // UUID команд у FTB и RYSTeams одни
                // все игроки команды уже в командах RYSTeams: земля уходит команде владельца
                List<UUID> members = team.members();
                boolean allPlaced = !members.isEmpty();
                for (UUID m : members) if (teams.teamOf(m).equals(m)) allPlaced = false;
                UUID owner = team.owner != null ? team.owner : members.isEmpty() ? null : members.get(0);
                if (allPlaced && owner != null) {
                    UUID to = teams.teamOf(owner);
                    r.line("земля команды FTB " + team.name + " ушла команде RYSTeams владельца (" + to + ")");
                    return to;
                }
                legacy(plan.data, id, team.name, "ftb");
                TeamLand land = plan.data.landOrCreate(id);
                land.legacyMembers.addAll(members);
                land.legacyOwner = owner;
                r.skipped("команда FTB " + team.name, "пары в RYSTeams нет, земля осталась за старым UUID и защищена; её участники остались своими, управлять ею может только оператор");
                return id;
            }
        }
    }

    private static void legacy(ClaimData data, UUID id, String name, String source) {
        TeamLand land = data.landOrCreate(id);
        if (land.legacyName == null) {
            land.legacyName = name;
            land.legacySource = source;
        }
    }

    /** Права и переключатели команды FTB (свойства {@code ftbchunks:*}). */
    static void properties(FtbWorld.Team t, TeamLand land) {
        FtbSettings.setFrom(land.rights, Rights.Action.BUILD, t.property("ftbchunks:block_edit_mode", null));
        String interact = t.property("ftbchunks:block_interact_mode", null);
        FtbSettings.setFrom(land.rights, Rights.Action.OPEN, interact);
        FtbSettings.setFrom(land.rights, Rights.Action.USE, interact);
        FtbSettings.setFrom(land.rights, Rights.Action.ENTITY, FtbSettings.stricter(
            t.property("ftbchunks:entity_interact_mode", null), t.property("ftbchunks:nonliving_entity_attack_mode", null)));
        land.flag(TeamLand.Flag.OWN_EXPLOSIONS, t.flag("ftbchunks:allow_explosions", TeamLand.Flag.OWN_EXPLOSIONS.def));
        land.flag(TeamLand.Flag.MOB_GRIEF, t.flag("ftbchunks:allow_mob_griefing", TeamLand.Flag.MOB_GRIEF.def));
        land.flag(TeamLand.Flag.PVP, t.flag("ftbchunks:allow_pvp", TeamLand.Flag.PVP.def));
        land.flag(TeamLand.Flag.MACHINES_PROFILE, t.flag("ftbchunks:allow_fake_players_by_id", TeamLand.Flag.MACHINES_PROFILE.def));
        land.flag(TeamLand.Flag.MACHINES_OUTSIDE, t.flag("ftbchunks:allow_fake_players", TeamLand.Flag.MACHINES_OUTSIDE.def));
        land.flag(TeamLand.Flag.MACHINES_INSIDE, true); // правило места наше новое, после переезда включено
        for (JsonElement n : Snbt.array(t.properties, "ftbchunks:allow_named_fake_players")) {
            if (n.isJsonPrimitive() && !land.machineNames.contains(n.getAsString())) land.machineNames.add(n.getAsString());
        }
        land.visibility = switch (t.property("ftbchunks:claim_visibility", "public")) {
            case "private" -> TeamLand.Visibility.TEAM;
            case "allies" -> TeamLand.Visibility.ALLIES;
            default -> TeamLand.Visibility.ALL;
        };
    }

    private static Collection<UUID> members(Teams teams, UUID team) {
        return teams.members(team);
    }

    private static String name(@Nullable FtbWorld.Team t, UUID id) {
        return t == null ? id.toString().substring(0, 8) : t.name;
    }

    private static String label(Plan plan, UUID team) {
        TeamLand l = plan.data.land(team);
        if (l != null && l.legacyName != null) return l.legacyName;
        return plan.names.getOrDefault(team, team.toString().substring(0, 8));
    }

    /** Игроки с доступом в FTB: участники и союзники всех команд с землёй. */
    public static Set<UUID> ftbAccess(FtbWorld w) {
        Set<UUID> out = new LinkedHashSet<>();
        for (FtbWorld.Land land : w.lands.values()) {
            if (land.chunkCount() == 0) continue;
            FtbWorld.Team t = w.teams.get(land.id);
            if (t == null) continue;
            out.addAll(t.members());
            out.addAll(t.allies());
        }
        return out;
    }

    static Settings copy(Settings b) {
        Settings s = new Settings();
        s.claimsPerTeam = b.claimsPerTeam;
        s.forcePerTeam = b.forcePerTeam;
        s.claimsPerMember = b.claimsPerMember;
        s.forcePerMember = b.forcePerMember;
        s.bonusMode = b.bonusMode;
        s.hardClaims = b.hardClaims;
        s.hardForce = b.hardForce;
        s.disabledDimensions = new LinkedHashSet<>(b.disabledDimensions);
        s.onlyDimensions = new LinkedHashSet<>(b.onlyDimensions);
        s.adjacentOnly = b.adjacentOnly;
        s.releaseSeconds = b.releaseSeconds;
        s.maxLoad = b.maxLoad;
        s.protection = b.protection;
        s.allyMode = b.allyMode;
        s.pvpMode = b.pvpMode;
        s.noWilderness = b.noWilderness;
        s.noWildernessDimensions = new LinkedHashSet<>(b.noWildernessDimensions);
        s.teamsEditRights = b.teamsEditRights;
        s.defaultRights = b.defaultRights.copy();
        return s;
    }
}

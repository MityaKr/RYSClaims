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
        for (String e : w.errors) r.partial(Say.t("file_unread", "file could not be read: %s", e));

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
            // последний вход из FTB: от него считается простой команды
            dst.lastSeen = Math.max(dst.lastSeen, land.lastLogin);
            if (configured.add(to)) {
                if (team != null) properties(team, dst);
            } else {
                r.line(Say.t("merged", "land of FTB team %s was added to the one already migrated; rights taken from the first", name(team, land.id)));
            }
            // союзники FTB это игроки: становятся доверенными, доступ не теряется
            if (team != null) {
                for (UUID ally : team.allies()) {
                    if (dst.trusted.add(ally)) r.count("trusted", 1);
                    if (!teams.teamOf(ally).equals(ally)) {
                        r.line(Say.t("trusted_in_team", "trusted player %s is in an RYSTeams team: can be replaced with a team alliance", ally));
                    }
                }
            }
            int expiring = 0;
            for (Map.Entry<String, List<FtbWorld.Chunk>> e : land.chunks.entrySet()) {
                String dim = e.getKey();
                if (!s.claimable(dim)) r.line(Say.t("closed_dim", "%s: land in %s was migrated although claiming is closed there: not taking it away", name(team, land.id), dim));
                for (FtbWorld.Chunk c : e.getValue()) {
                    long pos = Chunks.pack(c.x(), c.z());
                    Claim claim = data.put(dim, pos, to, null, c.time());
                    if (claim == null) {
                        UUID owner = data.owner(dim, pos);
                        plan.mismatches.add("MISMATCH " + dim + " " + Chunks.text(pos) + ": claimed twice (" + land.id + " and " + owner + ")");
                        continue;
                    }
                    if (c.forceLoaded() != 0) data.setForce(claim, true);
                    if (c.expiry() != 0) expiring++;
                }
            }
            if (expiring > 0) r.line(Say.t("expiring", "%s: %s chunks had an FTB expiry; the server mode now sets the expiry", name(team, land.id), expiring));
            if (land.preventedAccess > 0) r.line(Say.t("journal", "%s: %s FTB journal entries were not migrated", name(team, land.id), land.preventedAccess));
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
                r.line(Say.t("bonus_claims", "%s: team bonus +%s chunks so the limit is not below FTB", label(plan, team), needClaims - lim.claims));
            }
            if (lim.force < needForce) {
                land.bonusForce += needForce - lim.force;
                r.line(Say.t("bonus_force", "%s: team bonus +%s force-loaded chunks so the limit is not below FTB", label(plan, team), needForce - lim.force));
            }
            Limits after = Limits.of(s, land, members(teams, team), teams.leader(team), data::bonus);
            int claimed = data.claimed(team), forced = data.forced(team);
            r.line(Say.t("totals", "%s: chunks %s → %s, force-loaded %s → %s, FTB limits %s/%s → %s/%s", label(plan, team), f[0], claimed, f[1], forced,
                f[2], f[3], after.claims, after.force));
            if (claimed != f[0] || forced != f[1]) {
                plan.mismatches.add("MISMATCH " + label(plan, team) + ": FTB chunks " + f[0] + ", now " + claimed + "; FTB force-loaded " + f[1] + ", now " + forced);
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
            r.skipped(Say.t("land_of", "land %s", id), Say.t("no_team_file", "no FTB team file, kept under the old UUID"));
            return id;
        }
        switch (team.type) {
            case "player" -> {
                return id; // личная команда игрока: личная земля
            }
            case "server" -> {
                legacy(plan.data, id, team.name, "ftb-server");
                r.line(Say.t("server_team", "land of FTB server team %s stayed with it (%s)", team.name, id));
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
                    r.line(Say.t("moved_to_team", "land of FTB team %s moved to the owner's RYSTeams team (%s)", team.name, to));
                    return to;
                }
                legacy(plan.data, id, team.name, "ftb");
                TeamLand land = plan.data.landOrCreate(id);
                land.legacyMembers.addAll(members);
                land.legacyOwner = owner;
                r.skipped(Say.t("ftb_team", "FTB team %s", team.name), Say.t("no_pair", "no RYSTeams counterpart; the land stayed under the old UUID and is protected; its members remain its own, only an operator can manage it"));
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

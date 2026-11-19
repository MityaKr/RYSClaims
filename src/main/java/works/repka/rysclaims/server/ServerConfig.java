package works.repka.rysclaims.server;

import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.neoforged.neoforge.common.ModConfigSpec;
import works.repka.rysclaims.data.Rights;
import works.repka.rysclaims.data.Settings;

/**
 * Настройки сервера: {@code config/rysclaims-server.toml} (NeoForge 21.1 кладёт серверные настройки в общую папку config). Значения по умолчанию из плана, раздел 11
 * («Настройки сервера и значения по умолчанию»). Код читает их одним снимком {@link #snapshot()}: он пересобирается при
 * загрузке и правке файла.
 */
public final class ServerConfig {
    public static final ModConfigSpec SPEC;

    private static final ModConfigSpec.IntValue CLAIMS, FORCE, CLAIMS_MEMBER, FORCE_MEMBER, HARD_CLAIMS, HARD_FORCE, RELEASE, LOG_DAYS;
    private static final ModConfigSpec.IntValue IDLE_UNFORCE, IDLE_UNCLAIM, BUFFER;
    private static final ModConfigSpec.ConfigValue<String> BONUS_MODE, MAX_LOAD, ALLY_MODE, PVP_MODE, OWNERLESS;
    private static final ModConfigSpec.BooleanValue FLUIDS, FIRE, PISTONS, HOPPERS;
    private static final ModConfigSpec.ConfigValue<List<? extends String>> DISABLED, ONLY, NO_WILD_DIMS, LOCKED;
    private static final ModConfigSpec.BooleanValue ADJACENT, PROTECTION, NO_WILD, EDIT_RIGHTS;
    private static final ModConfigSpec.ConfigValue<String> DEF_BUILD, DEF_OPEN, DEF_USE, DEF_ENTITY;

    private static volatile Settings cached;

    static {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();
        b.push("limits");
        CLAIMS = b.comment("Claimed chunks per team").defineInRange("claimsPerTeam", 500, 0, 1_000_000);
        FORCE = b.comment("Force loaded chunks per team").defineInRange("forcePerTeam", 25, 0, 1_000_000);
        CLAIMS_MEMBER = b.comment("Extra claims for every member after the first").defineInRange("claimsPerMember", 0, 0, 100_000);
        FORCE_MEMBER = b.comment("Extra force loaded chunks for every member after the first").defineInRange("forcePerMember", 0, 0, 100_000);
        BONUS_MODE = b.comment("How personal bonuses of members add up in a team: largest, sum, leader, average")
            .define("personalBonusMode", "largest", o -> o instanceof String s && Settings.BonusMode.parse(s, null) != null);
        HARD_CLAIMS = b.comment("Hard cap on team claims including all bonuses, 0 means no cap").defineInRange("hardClaimLimit", 0, 0, 1_000_000);
        HARD_FORCE = b.comment("Hard cap on team force loaded chunks, 0 means no cap").defineInRange("hardForceLimit", 0, 0, 1_000_000);
        b.pop();

        b.push("land");
        DISABLED = b.comment("Dimensions where land cannot be claimed; \"mod:*\" closes every dimension of a mod")
            .defineListAllowEmpty("disabledDimensions", List.of("ae2:spatial_storage", "neovitae:dungeon"), () -> "mod:dimension", o -> o instanceof String);
        ONLY = b.comment("If not empty, land can be claimed only in these dimensions")
            .defineListAllowEmpty("onlyDimensions", List.of(), () -> "mod:dimension", o -> o instanceof String);
        ADJACENT = b.comment("A new chunk must touch the team's land by a side (or be its first chunk in the dimension)").define("adjacentOnly", false);
        BUFFER = b.comment("Another team (not an ally) cannot claim a chunk closer than this many chunks to a team's land, 0 means no buffer")
            .defineInRange("bufferChunks", 0, 0, 16);
        RELEASE = b.comment("Seconds a released chunk stays protected and can be kept").defineInRange("releaseSeconds", 30, 0, 3600);
        MAX_LOAD = b.comment("Most generous force loading a team may choose: online (while a member is online) or always")
            .define("maxForceLoadMode", "online", o -> o instanceof String s && Settings.LoadMode.parse(s, null) != null);
        IDLE_UNFORCE = b.comment("Days without any team member logging in after which the team's force loading is switched off, 0 means never")
            .defineInRange("idleDaysBeforeUnforce", 0, 0, 3650);
        IDLE_UNCLAIM = b.comment("Days without any team member logging in after which the team's land is released (a warning goes to the team feed a week before), 0 means never")
            .defineInRange("idleDaysBeforeUnclaim", 0, 0, 3650);
        b.pop();

        b.push("access");
        PROTECTION = b.comment("Land protection; false keeps claims and force loading only").define("protection", true);
        ALLY_MODE = b.comment("Who counts as allies: team (as the team decides), all, none")
            .define("allyMode", "team", o -> o instanceof String s && Settings.AllyMode.parse(s, null) != null);
        PVP_MODE = b.comment("Player combat on claimed land: always, team (the team decides), never")
            .define("pvpMode", "always", o -> o instanceof String s && Settings.PvpMode.parse(s, null) != null);
        NO_WILD = b.comment("Unclaimed land: building is not allowed anywhere").define("noWilderness", false);
        NO_WILD_DIMS = b.comment("Unclaimed land: building is not allowed in these dimensions (overrides noWilderness)")
            .defineListAllowEmpty("noWildernessDimensions", List.of(), () -> "mod:dimension", o -> o instanceof String);
        EDIT_RIGHTS = b.comment("Teams may change their own rights and switches").define("teamsEditRights", true);
        LOG_DAYS = b.comment("Days the log of denied attempts keeps its lines").defineInRange("logDays", 7, 1, 365);
        LOCKED = b.comment("Team switches fixed by the server, \"switch=on\" or \"switch=off\": ownExplosions, mobGrief, pvp, machinesInside, machinesOutside, machinesProfile")
            .defineListAllowEmpty("lockedSwitches", List.of(), () -> "pvp=on", o -> o instanceof String str && Settings.parseLock(str) != null);
        b.pop();

        b.push("borders");
        FLUIDS = b.comment("Water and lava may flow onto another team's land (from other land or unclaimed land)").define("fluidsAcrossBorders", false);
        FIRE = b.comment("Fire and lightning fire may spread onto another team's land").define("fireAcrossBorders", false);
        PISTONS = b.comment("Pistons may move blocks across the border of land, falling blocks may land across it").define("pistonsAcrossBorders", false);
        HOPPERS = b.comment("Hoppers, hopper minecarts, droppers and dispensers may work across the border of land").define("hoppersAcrossBorders", false);
        OWNERLESS = b.comment("Explosions without an owner (redstone TNT, beds, end crystals, cannon shells): own (only on the land where they go off and only with the team switch), never, always")
            .define("ownerlessExplosions", "own", o -> o instanceof String s && Settings.Ownerless.parse(s, null) != null);
        b.pop();

        b.push("newTeams");
        b.comment("Rights of a new team per action: team, team_and_allies, open");
        DEF_BUILD = b.define("build", "team", ServerConfig::isPreset);
        DEF_OPEN = b.define("open", "team_and_allies", ServerConfig::isPreset);
        DEF_USE = b.define("use", "team_and_allies", ServerConfig::isPreset);
        DEF_ENTITY = b.define("entity", "team", ServerConfig::isPreset);
        b.pop();
        SPEC = b.build();
    }

    private ServerConfig() {}

    private static boolean isPreset(Object o) {
        return o instanceof String s && Rights.Preset.parse(s) != null;
    }

    /** Сбрасывает снимок: зовётся при загрузке и правке файла. */
    public static void invalidate() {
        cached = null;
    }

    /** Настройки одним снимком. До загрузки файла (или вне сервера) значения по умолчанию. */
    public static Settings snapshot() {
        Settings s = cached;
        if (s != null) return s;
        s = new Settings();
        if (SPEC.isLoaded()) {
            s.claimsPerTeam = CLAIMS.get();
            s.forcePerTeam = FORCE.get();
            s.claimsPerMember = CLAIMS_MEMBER.get();
            s.forcePerMember = FORCE_MEMBER.get();
            s.bonusMode = Settings.BonusMode.parse(BONUS_MODE.get(), Settings.BonusMode.LARGEST);
            s.hardClaims = HARD_CLAIMS.get();
            s.hardForce = HARD_FORCE.get();
            s.disabledDimensions = new LinkedHashSet<>(DISABLED.get());
            s.onlyDimensions = new LinkedHashSet<>(ONLY.get());
            s.adjacentOnly = ADJACENT.get();
            s.bufferChunks = BUFFER.get();
            s.releaseSeconds = RELEASE.get();
            s.idleUnforceDays = IDLE_UNFORCE.get();
            s.idleUnclaimDays = IDLE_UNCLAIM.get();
            s.maxLoad = Settings.LoadMode.parse(MAX_LOAD.get(), Settings.LoadMode.ONLINE);
            s.protection = PROTECTION.get();
            s.allyMode = Settings.AllyMode.parse(ALLY_MODE.get(), Settings.AllyMode.TEAM);
            s.pvpMode = Settings.PvpMode.parse(PVP_MODE.get(), Settings.PvpMode.ALWAYS);
            s.noWilderness = NO_WILD.get();
            s.noWildernessDimensions = new LinkedHashSet<>(NO_WILD_DIMS.get());
            s.teamsEditRights = EDIT_RIGHTS.get();
            s.logDays = LOG_DAYS.get();
            for (String l : LOCKED.get()) {
                var e = Settings.parseLock(l);
                if (e != null) s.lockedFlags.put(e.getKey(), e.getValue());
            }
            s.fluidsCross = FLUIDS.get();
            s.fireCross = FIRE.get();
            s.pistonsCross = PISTONS.get();
            s.hoppersCross = HOPPERS.get();
            s.ownerless = Settings.Ownerless.parse(OWNERLESS.get(), Settings.Ownerless.OWN);
            Rights r = new Rights();
            r.set(Rights.Action.BUILD, preset(DEF_BUILD));
            r.set(Rights.Action.OPEN, preset(DEF_OPEN));
            r.set(Rights.Action.USE, preset(DEF_USE));
            r.set(Rights.Action.ENTITY, preset(DEF_ENTITY));
            s.defaultRights = r;
            cached = s;
        }
        return s;
    }

    private static List<ModConfigSpec.ConfigValue<?>> all() {
        return List.of(CLAIMS, FORCE, CLAIMS_MEMBER, FORCE_MEMBER, HARD_CLAIMS, HARD_FORCE, RELEASE, BONUS_MODE, MAX_LOAD, ALLY_MODE,
            PVP_MODE, DISABLED, ONLY, NO_WILD_DIMS, ADJACENT, PROTECTION, NO_WILD, EDIT_RIGHTS, DEF_BUILD, DEF_OPEN, DEF_USE, DEF_ENTITY,
            LOG_DAYS, FLUIDS, FIRE, PISTONS, HOPPERS, OWNERLESS, LOCKED, IDLE_UNFORCE, IDLE_UNCLAIM, BUFFER);
    }

    /**
     * Файл ещё не трогали: все значения по умолчанию. Игра создаёт файл до запуска шагов переезда, поэтому «файла ещё нет»
     * из плана (10.5) проверяется так.
     */
    public static boolean untouched() {
        if (!SPEC.isLoaded()) return false;
        for (ModConfigSpec.ConfigValue<?> v : all()) if (!java.util.Objects.equals(v.get(), v.getDefault())) return false;
        return true;
    }

    /** Записывает настройки в файл (перенос настроек FTB). */
    public static void write(Settings s) {
        if (!SPEC.isLoaded()) return;
        CLAIMS.set(s.claimsPerTeam);
        FORCE.set(s.forcePerTeam);
        CLAIMS_MEMBER.set(s.claimsPerMember);
        FORCE_MEMBER.set(s.forcePerMember);
        BONUS_MODE.set(s.bonusMode.key());
        HARD_CLAIMS.set(s.hardClaims);
        HARD_FORCE.set(s.hardForce);
        DISABLED.set(List.copyOf(s.disabledDimensions));
        ONLY.set(List.copyOf(s.onlyDimensions));
        ADJACENT.set(s.adjacentOnly);
        BUFFER.set(s.bufferChunks);
        RELEASE.set(s.releaseSeconds);
        IDLE_UNFORCE.set(s.idleUnforceDays);
        IDLE_UNCLAIM.set(s.idleUnclaimDays);
        MAX_LOAD.set(s.maxLoad.key());
        PROTECTION.set(s.protection);
        ALLY_MODE.set(s.allyMode.name().toLowerCase(java.util.Locale.ROOT));
        PVP_MODE.set(s.pvpMode.name().toLowerCase(java.util.Locale.ROOT));
        NO_WILD.set(s.noWilderness);
        NO_WILD_DIMS.set(List.copyOf(s.noWildernessDimensions));
        EDIT_RIGHTS.set(s.teamsEditRights);
        LOG_DAYS.set(s.logDays);
        FLUIDS.set(s.fluidsCross);
        FIRE.set(s.fireCross);
        PISTONS.set(s.pistonsCross);
        HOPPERS.set(s.hoppersCross);
        OWNERLESS.set(s.ownerless.key());
        DEF_BUILD.set(s.defaultRights.presetOf(Rights.Action.BUILD).key());
        DEF_OPEN.set(s.defaultRights.presetOf(Rights.Action.OPEN).key());
        DEF_USE.set(s.defaultRights.presetOf(Rights.Action.USE).key());
        DEF_ENTITY.set(s.defaultRights.presetOf(Rights.Action.ENTITY).key());
        SPEC.save();
        invalidate();
    }

    /**
     * Настройки, которые оператор правит в окне (макет 51): ключ окна → значение файла. Сохраняется сразу, снимок
     * сбрасывается. Списки (измерения, профили) правятся в файле.
     */
    private static Map<String, ModConfigSpec.ConfigValue<?>> editable() {
        Map<String, ModConfigSpec.ConfigValue<?>> m = new LinkedHashMap<>();
        m.put("claimsPerTeam", CLAIMS);
        m.put("forcePerTeam", FORCE);
        m.put("claimsPerMember", CLAIMS_MEMBER);
        m.put("forcePerMember", FORCE_MEMBER);
        m.put("adjacentOnly", ADJACENT);
        m.put("bufferChunks", BUFFER);
        m.put("ownerlessExplosions", OWNERLESS);
        m.put("pistonsAcrossBorders", PISTONS);
        m.put("fluidsAcrossBorders", FLUIDS);
        m.put("fireAcrossBorders", FIRE);
        m.put("hoppersAcrossBorders", HOPPERS);
        m.put("pvpMode", PVP_MODE);
        m.put("noWilderness", NO_WILD);
        m.put("allyMode", ALLY_MODE);
        m.put("maxForceLoadMode", MAX_LOAD);
        m.put("build", DEF_BUILD);
        m.put("open", DEF_OPEN);
        m.put("use", DEF_USE);
        m.put("entity", DEF_ENTITY);
        m.put("teamsEditRights", EDIT_RIGHTS);
        m.put("logDays", LOG_DAYS);
        m.put("idleDaysBeforeUnforce", IDLE_UNFORCE);
        m.put("idleDaysBeforeUnclaim", IDLE_UNCLAIM);
        return m;
    }

    /** Значения правимых настроек строками (для окна). */
    public static Map<String, String> values() {
        Map<String, String> out = new LinkedHashMap<>();
        if (!SPEC.isLoaded()) return out;
        editable().forEach((k, v) -> out.put(k, String.valueOf(v.get())));
        return out;
    }

    /** Правка из окна: проверка значения, запись в файл, сброс снимка. Возвращает, принято ли. */
    @SuppressWarnings("unchecked")
    public static boolean set(String key, String value) {
        if (!SPEC.isLoaded()) return false;
        ModConfigSpec.ConfigValue<?> v = editable().get(key);
        if (v == null) return false;
        try {
            if (v instanceof ModConfigSpec.IntValue iv) {
                int n = Integer.parseInt(value.trim());
                if (!iv.getSpec().test(n)) return false;
                iv.set(n);
            } else if (v instanceof ModConfigSpec.BooleanValue bv) {
                bv.set(Boolean.parseBoolean(value.trim()));
            } else {
                ModConfigSpec.ConfigValue<String> sv = (ModConfigSpec.ConfigValue<String>) v;
                if (!sv.getSpec().test(value)) return false;
                sv.set(value);
            }
        } catch (RuntimeException e) {
            return false;
        }
        SPEC.save();
        invalidate();
        return true;
    }

    private static Rights.Preset preset(ModConfigSpec.ConfigValue<String> v) {
        Rights.Preset p = Rights.Preset.parse(v.get());
        return p == null ? Rights.Preset.TEAM : p;
    }
}

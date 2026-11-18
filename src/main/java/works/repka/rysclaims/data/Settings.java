package works.repka.rysclaims.data;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Настройки сервера одним снимком, без игры: их читают сервис и проверки. Живые значения собирает
 * {@code server.ServerConfig#snapshot} из {@code rysclaims-server.toml}, проверки строят снимок сами.
 */
public final class Settings {
    /** Как личные добавки участников складываются в команде (FTB {@code party_limit_mode}). */
    public enum BonusMode {
        /** Наибольшая из личных добавок (FTB {@code largest}, RepYS). */
        LARGEST,
        /** Сумма личных добавок. */
        SUM,
        /** Только добавка лидера (FTB {@code owner}). */
        LEADER,
        /** Средняя, с округлением вниз. */
        AVERAGE;

        public String key() {
            return name().toLowerCase(Locale.ROOT);
        }

        public static BonusMode parse(String key, BonusMode fallback) {
            if ("owner".equalsIgnoreCase(key)) return LEADER;
            for (BonusMode m : values()) if (m.key().equalsIgnoreCase(key)) return m;
            return fallback;
        }
    }

    /** Прогрузка: пока кто-то из команды в сети или всегда. Режим «и ещё N часов» придёт в 0.2. */
    public enum LoadMode {
        ONLINE, ALWAYS;

        public String key() {
            return name().toLowerCase(Locale.ROOT);
        }

        public static LoadMode parse(String key, LoadMode fallback) {
            for (LoadMode m : values()) if (m.key().equalsIgnoreCase(key)) return m;
            return fallback;
        }
    }

    /** Кого считать союзниками: как решит команда, всех, никого (FTB {@code ally_mode}). */
    public enum AllyMode {
        TEAM, ALL, NONE;

        public static AllyMode parse(String key, AllyMode fallback) {
            if ("default".equalsIgnoreCase(key)) return TEAM;
            if ("forced_all".equalsIgnoreCase(key)) return ALL;
            if ("forced_none".equalsIgnoreCase(key)) return NONE;
            for (AllyMode m : values()) if (m.name().equalsIgnoreCase(key)) return m;
            return fallback;
        }
    }

    /** Взрывы без хозяина (ТНТ от редстоуна, кровать, кристалл): только свои на своей земле, никогда, всегда. */
    public enum Ownerless {
        OWN, NEVER, ALWAYS;

        public String key() {
            return name().toLowerCase(Locale.ROOT);
        }

        public static Ownerless parse(String key, Ownerless fallback) {
            for (Ownerless m : values()) if (m.key().equalsIgnoreCase(key)) return m;
            return fallback;
        }
    }

    /** Бой на земле: везде, решает команда, нигде на участках (FTB {@code pvp_mode}). */
    public enum PvpMode {
        ALWAYS, TEAM, NEVER;

        public static PvpMode parse(String key, PvpMode fallback) {
            if ("per_team".equalsIgnoreCase(key)) return TEAM;
            for (PvpMode m : values()) if (m.name().equalsIgnoreCase(key)) return m;
            return fallback;
        }
    }

    // ---- лимиты ----
    public int claimsPerTeam = 500;
    public int forcePerTeam = 25;
    /** Добавка за каждого участника сверх первого. */
    public int claimsPerMember = 0;
    public int forcePerMember = 0;
    public BonusMode bonusMode = BonusMode.LARGEST;
    /** Потолки лимита команды; 0: без потолка. */
    public int hardClaims = 0;
    public int hardForce = 0;

    // ---- земля ----
    /** Измерения без привата; {@code мод:*} закрывает все измерения мода. */
    public Set<String> disabledDimensions = new LinkedHashSet<>(List.of("ae2:spatial_storage", "neovitae:dungeon"));
    /** Приват только в этих измерениях; пусто: везде, кроме закрытых. */
    public Set<String> onlyDimensions = new LinkedHashSet<>();
    /** Новый чанк только рядом со своим (по стороне) или первым в измерении. */
    public boolean adjacentOnly = false;
    /** Сколько секунд освобождаемый чанк ещё защищён. */
    public int releaseSeconds = 30;
    /** Самая щедрая прогрузка, которую может выбрать команда. */
    public LoadMode maxLoad = LoadMode.ONLINE;

    // ---- доступ ----
    public boolean protection = true;
    public AllyMode allyMode = AllyMode.TEAM;
    public PvpMode pvpMode = PvpMode.ALWAYS;
    /** Свободная земля: строить нельзя (везде или в списке измерений). */
    public boolean noWilderness = false;
    public Set<String> noWildernessDimensions = new LinkedHashSet<>();
    /** Могут ли команды менять свои права и переключатели. */
    public boolean teamsEditRights = true;
    /** Переключатели, закреплённые сервером: команда их видит, но не меняет (план, раздел 6). */
    public java.util.EnumMap<TeamLand.Flag, Boolean> lockedFlags = new java.util.EnumMap<>(TeamLand.Flag.class);
    // ---- через границу земли ----
    /** Вода и лава текут с чужой или свободной земли на землю команды. */
    public boolean fluidsCross = false;
    /** Огонь и молния переходят на землю другой команды. */
    public boolean fireCross = false;
    /** Поршни двигают блоки через границу земли (и падающие блоки ложатся через неё). */
    public boolean pistonsCross = false;
    /** Воронки, выбрасыватели и раздатчики работают через границу земли. */
    public boolean hoppersCross = false;
    /** Взрывы без хозяина. */
    public Ownerless ownerless = Ownerless.OWN;
    /** Сколько дней журнал отказов хранит записи. */
    public int logDays = 7;
    /** Права новой команды. */
    public Rights defaultRights = defaultRights();

    public static Rights defaultRights() {
        Rights r = Rights.of(Rights.Preset.TEAM);
        r.set(Rights.Action.OPEN, Rights.Preset.TEAM_AND_ALLIES);
        r.set(Rights.Action.USE, Rights.Preset.TEAM_AND_ALLIES);
        return r;
    }

    /** Строка {@code pvp=on} настройки закреплённых переключателей или null. */
    public static java.util.Map.@org.jetbrains.annotations.Nullable Entry<TeamLand.Flag, Boolean> parseLock(String line) {
        int eq = line.indexOf('=');
        if (eq <= 0) return null;
        TeamLand.Flag f = TeamLand.Flag.parse(line.substring(0, eq).trim());
        String v = line.substring(eq + 1).trim().toLowerCase(Locale.ROOT);
        if (f == null || !(v.equals("on") || v.equals("off") || v.equals("true") || v.equals("false"))) return null;
        return java.util.Map.entry(f, v.equals("on") || v.equals("true"));
    }

    /** Разрешён ли приват в измерении. */
    public boolean claimable(String dimension) {
        if (matches(disabledDimensions, dimension)) return false;
        return onlyDimensions.isEmpty() || matches(onlyDimensions, dimension);
    }

    /** Закрыта ли свободная земля в измерении. */
    public boolean wildernessClosed(String dimension) {
        if (!noWildernessDimensions.isEmpty()) return matches(noWildernessDimensions, dimension);
        return noWilderness;
    }

    static boolean matches(Set<String> list, String dimension) {
        if (list.contains(dimension)) return true;
        int colon = dimension.indexOf(':');
        return colon > 0 && list.contains(dimension.substring(0, colon) + ":*");
    }
}

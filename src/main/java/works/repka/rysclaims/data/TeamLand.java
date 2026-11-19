package works.repka.rysclaims.data;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import org.jetbrains.annotations.Nullable;

/**
 * Земля одной команды: добавка к лимиту от оператора, режим прогрузки, видимость, права, переключатели, доверенные. Сами
 * чанки лежат в {@link ClaimData}, здесь только счётчики.
 */
public final class TeamLand {
    /** Переключатели команды. Имена совпадают с ключами NBT и командой {@code /claims flag}. */
    public enum Flag {
        /** Свои взрывы ломают блоки на земле команды. */
        OWN_EXPLOSIONS("ownExplosions", false),
        /** Мобы портят блоки. */
        MOB_GRIEF("mobGrief", false),
        /** Бой между игроками (если сервер отдал решение командам). */
        PVP("pvp", true),
        /** Машины на земле команды работают по её земле. */
        MACHINES_INSIDE("machinesInside", true),
        /** Машины снаружи работают по земле команды. */
        MACHINES_OUTSIDE("machinesOutside", false),
        /** Фейковый игрок с профилем участника или доверенного действует с его правами ({@code allow_fake_players_by_id}). */
        MACHINES_PROFILE("machinesProfile", true);

        public final String key;
        public final boolean def;

        Flag(String key, boolean def) {
            this.key = key;
            this.def = def;
        }

        public static @Nullable Flag parse(String key) {
            for (Flag f : values()) if (f.key.equalsIgnoreCase(key)) return f;
            return null;
        }
    }

    /** Кто видит землю на карте. */
    public enum Visibility {
        ALL, ALLIES, TEAM;

        public String key() {
            return name().toLowerCase(Locale.ROOT);
        }

        public static Visibility parse(String key, Visibility fallback) {
            for (Visibility v : values()) if (v.key().equals(key)) return v;
            return fallback;
        }
    }

    public final UUID id;
    public int bonusClaims, bonusForce;
    /** Когда участник команды был в сети последний раз, мс от эпохи. */
    public long lastSeen;
    /** Предупреждение о снятии земли по простою уже ушло в ленту (сбрасывается входом участника). */
    public boolean idleWarned;
    /** Режим прогрузки, который выбрала команда в пределах сервера. */
    public Settings.LoadMode loadMode = Settings.LoadMode.ONLINE;
    /** Часы после выхода для режима «и ещё N часов» (0.2). */
    public int loadHours;
    public Visibility visibility = Visibility.ALL;
    /** Журнал отказов: писать попытки чужих в ленту команды (одной строкой, повторы склеены) и тост участникам в сети. */
    public boolean logFeed = true, logToast = false;
    /** С какого времени земли больше лимита (после слияния); через 7 дней лишнее освобождается. 0: в пределах. */
    public long overSince;
    public Rights rights;
    private final boolean[] flags = new boolean[Flag.values().length];
    public final Set<UUID> trusted = new LinkedHashSet<>();
    public final List<String> machineNames = new ArrayList<>();
    /** Команда FTB без пары в RYSTeams: имя и источник. */
    public @Nullable String legacyName, legacySource;
    /**
     * Участники команды FTB без пары: на её земле они остаются «своими» (доступ по столбцу команды, владелец FTB как
     * лидер), но управлять землёй не могут, пока оператор не решит её судьбу.
     */
    public final Set<UUID> legacyMembers = new LinkedHashSet<>();
    public @Nullable UUID legacyOwner;
    /** Участки по номерам (план, раздел 6). Номера не переиспользуются: {@link #nextPlot}. */
    public final java.util.Map<Integer, Plot> plots = new java.util.LinkedHashMap<>();
    public int nextPlot = 1;

    int claimed, forced;

    public TeamLand(UUID id, Rights defaults) {
        this.id = id;
        this.rights = defaults.copy();
        for (Flag f : Flag.values()) flags[f.ordinal()] = f.def;
    }

    public boolean flag(Flag f) {
        return flags[f.ordinal()];
    }

    public void flag(Flag f, boolean on) {
        flags[f.ordinal()] = on;
    }

    /** Чанков у команды, включая освобождаемые. */
    public int claimed() {
        return claimed;
    }

    /** Прогруженных чанков, включая освобождаемые. */
    public int forced() {
        return forced;
    }

    /** Есть ли что хранить: пустая запись без чанков и без правок в файл не пишется. */
    boolean worthSaving(Rights defaults) {
        if (claimed > 0 || bonusClaims != 0 || bonusForce != 0 || !trusted.isEmpty() || !machineNames.isEmpty()) return true;
        if (legacyName != null || loadMode != Settings.LoadMode.ONLINE || visibility != Visibility.ALL || !plots.isEmpty()) return true;
        if (!logFeed || logToast || overSince != 0) return true;
        if (!rights.equals(defaults)) return true;
        for (Flag f : Flag.values()) if (flag(f) != f.def) return true;
        return false;
    }

    CompoundTag save() {
        CompoundTag t = new CompoundTag();
        t.putInt("bonusClaims", bonusClaims);
        t.putInt("bonusForce", bonusForce);
        t.putLong("lastSeen", lastSeen);
        if (idleWarned) t.putBoolean("idleWarned", true);
        t.putString("loadMode", loadMode.key());
        t.putInt("loadHours", loadHours);
        t.putString("visibility", visibility.key());
        t.putBoolean("logFeed", logFeed);
        t.putBoolean("logToast", logToast);
        if (overSince != 0) t.putLong("overSince", overSince);
        t.put("rights", rights.save());
        CompoundTag f = new CompoundTag();
        for (Flag x : Flag.values()) f.putBoolean(x.key, flag(x));
        t.put("flags", f);
        ListTag tr = new ListTag();
        for (UUID u : trusted) tr.add(StringTag.valueOf(u.toString()));
        t.put("trusted", tr);
        ListTag names = new ListTag();
        for (String n : machineNames) names.add(StringTag.valueOf(n));
        t.put("machineNames", names);
        if (legacyName != null) {
            CompoundTag l = new CompoundTag();
            l.putString("name", legacyName);
            l.putString("source", legacySource == null ? "" : legacySource);
            ListTag lm = new ListTag();
            for (UUID u : legacyMembers) lm.add(StringTag.valueOf(u.toString()));
            l.put("members", lm);
            if (legacyOwner != null) l.putUUID("owner", legacyOwner);
            t.put("legacy", l);
        }
        ListTag pl = new ListTag();
        for (Plot p : plots.values()) {
            CompoundTag one = new CompoundTag();
            one.putInt("id", p.id);
            one.putString("name", p.name);
            one.putString("dim", p.dim);
            pl.add(one);
        }
        t.put("plots", pl);
        t.putInt("nextPlot", nextPlot);
        return t;
    }

    static TeamLand load(UUID id, CompoundTag t, Rights defaults) {
        TeamLand land = new TeamLand(id, defaults);
        land.bonusClaims = t.getInt("bonusClaims");
        land.bonusForce = t.getInt("bonusForce");
        land.lastSeen = t.getLong("lastSeen");
        land.idleWarned = t.getBoolean("idleWarned");
        land.loadMode = Settings.LoadMode.parse(t.getString("loadMode"), Settings.LoadMode.ONLINE);
        land.loadHours = t.getInt("loadHours");
        land.visibility = Visibility.parse(t.getString("visibility"), Visibility.ALL);
        land.logFeed = !t.contains("logFeed") || t.getBoolean("logFeed");
        land.logToast = t.getBoolean("logToast");
        land.overSince = t.getLong("overSince");
        land.rights = Rights.load(t.getCompound("rights"), defaults);
        CompoundTag f = t.getCompound("flags");
        for (Flag x : Flag.values()) land.flag(x, f.contains(x.key) ? f.getBoolean(x.key) : x.def);
        for (Tag u : t.getList("trusted", Tag.TAG_STRING)) {
            try {
                land.trusted.add(UUID.fromString(u.getAsString()));
            } catch (IllegalArgumentException ignored) {
                // битая строка не мешает остальным
            }
        }
        for (Tag n : t.getList("machineNames", Tag.TAG_STRING)) land.machineNames.add(n.getAsString());
        if (t.contains("legacy", Tag.TAG_COMPOUND)) {
            CompoundTag l = t.getCompound("legacy");
            land.legacyName = l.getString("name");
            land.legacySource = l.getString("source");
            for (Tag u : l.getList("members", Tag.TAG_STRING)) {
                try {
                    land.legacyMembers.add(UUID.fromString(u.getAsString()));
                } catch (IllegalArgumentException ignored) {
                    // битая строка не мешает остальным
                }
            }
            if (l.hasUUID("owner")) land.legacyOwner = l.getUUID("owner");
        }
        for (Tag x : t.getList("plots", Tag.TAG_COMPOUND)) {
            CompoundTag one = (CompoundTag) x;
            int pid = one.getInt("id");
            if (pid <= 0 || !one.contains("dim")) continue;
            land.plots.put(pid, new Plot(pid, one.getString("name"), one.getString("dim")));
            land.nextPlot = Math.max(land.nextPlot, pid + 1);
        }
        land.nextPlot = Math.max(land.nextPlot, t.getInt("nextPlot"));
        return land;
    }
}

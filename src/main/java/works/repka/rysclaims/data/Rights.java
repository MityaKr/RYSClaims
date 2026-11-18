package works.repka.rysclaims.data;

import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import org.jetbrains.annotations.Nullable;

/**
 * Права доступа к земле команды: четыре действия на столбцы. Столбцы это роли RYSTeams ({@code leader}, {@code officer},
 * {@code member}, {@code guest}) и {@code allies}, {@code all}. В 0.1 таблица в три столбца: команда (лидер, офицер,
 * участник), союзники, все; гость получает права столбца «Союзники» (решение 18 плана).
 *
 * <p>Права монотонны: что разрешено всем, разрешено союзникам; что разрешено союзникам, разрешено команде. Лидеру своей
 * земли можно всё.
 */
public final class Rights {
    public enum Action {
        BUILD, OPEN, USE, ENTITY;

        public String key() {
            return name().toLowerCase(Locale.ROOT);
        }

        public static @Nullable Action parse(String key) {
            for (Action a : values()) if (a.key().equals(key)) return a;
            return null;
        }
    }

    /** Кем приходится игрок хозяину земли. */
    public enum Relation { TEAM, ALLY, OTHER }

    /** Готовые наборы. Таблица, не совпавшая ни с одним, это «Свои». */
    public enum Preset {
        TEAM, TEAM_AND_ALLIES, OPEN;

        public String key() {
            return name().toLowerCase(Locale.ROOT);
        }

        public static @Nullable Preset parse(String key) {
            for (Preset p : values()) if (p.key().equals(key)) return p;
            return null;
        }
    }

    public static final String LEADER = "leader", OFFICER = "officer", MEMBER = "member", GUEST = "guest";
    public static final String ALLIES = "allies", ALL = "all";
    /** Столбец «Команда» таблицы 0.1. */
    public static final List<String> TEAM_ROLES = List.of(LEADER, OFFICER, MEMBER);
    public static final List<String> COLUMNS = List.of(LEADER, OFFICER, MEMBER, GUEST, ALLIES, ALL);

    private final Map<Action, Set<String>> table = new EnumMap<>(Action.class);

    public Rights() {
        for (Action a : Action.values()) table.put(a, new LinkedHashSet<>(TEAM_ROLES));
    }

    public static Rights of(Preset preset) {
        Rights r = new Rights();
        r.apply(preset);
        return r;
    }

    public Rights copy() {
        Rights r = new Rights();
        for (Action a : Action.values()) {
            r.table.get(a).clear();
            r.table.get(a).addAll(table.get(a));
        }
        return r;
    }

    public void apply(Preset preset) {
        for (Action a : Action.values()) set(a, preset);
    }

    /** Одно действие по набору: так настройка сервера задаёт права новых команд построчно. */
    public void set(Action a, Preset preset) {
        Set<String> s = table.get(a);
        s.clear();
        s.addAll(TEAM_ROLES);
        if (preset != Preset.TEAM) s.add(ALLIES);
        if (preset == Preset.OPEN) s.add(ALL);
    }

    /** Набор, которому соответствует строка действия: «все» значит открытая, «союзники» значит с союзом. */
    public Preset presetOf(Action a) {
        Set<String> s = table.get(a);
        if (s.contains(ALL)) return Preset.OPEN;
        if (s.contains(ALLIES)) return Preset.TEAM_AND_ALLIES;
        return Preset.TEAM;
    }

    public @Nullable Preset preset() {
        for (Preset p : Preset.values()) if (equals(of(p))) return p;
        return null;
    }

    /** Есть ли у столбца отметка (без монотонности): так таблица хранится и показывается. */
    public boolean has(Action a, String column) {
        return table.get(a).contains(column);
    }

    /** Ставит или снимает отметку. Столбец {@code team} это все роли команды разом. Лидера снять нельзя. */
    public void set(Action a, String column, boolean on) {
        Set<String> s = table.get(a);
        if ("team".equals(column)) {
            for (String role : TEAM_ROLES) if (on || !LEADER.equals(role)) setOne(s, role, on);
            return;
        }
        if (!COLUMNS.contains(column)) throw new IllegalArgumentException("нет такого столбца: " + column);
        if (LEADER.equals(column) && !on) return;
        setOne(s, column, on);
    }

    private static void setOne(Set<String> s, String column, boolean on) {
        if (on) s.add(column);
        else s.remove(column);
    }

    /**
     * Можно ли действие. {@code role} это роль игрока в команде-хозяине (для {@link Relation#TEAM}), иначе не важна.
     */
    public boolean allows(Action a, Relation relation, @Nullable String role) {
        Set<String> s = table.get(a);
        boolean all = s.contains(ALL);
        boolean allies = all || s.contains(ALLIES);
        return switch (relation) {
            case OTHER -> all;
            case ALLY -> allies;
            case TEAM -> {
                if (role == null || LEADER.equals(role)) yield true;
                if (GUEST.equals(role)) yield allies || s.contains(GUEST);
                yield allies || s.contains(role);
            }
        };
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        for (Action a : Action.values()) {
            ListTag list = new ListTag();
            for (String c : table.get(a)) list.add(StringTag.valueOf(c));
            tag.put(a.key(), list);
        }
        return tag;
    }

    public static Rights load(CompoundTag tag, Rights fallback) {
        Rights r = fallback.copy();
        for (Action a : Action.values()) {
            if (!tag.contains(a.key(), Tag.TAG_LIST)) continue;
            Set<String> s = r.table.get(a);
            s.clear();
            for (Tag t : tag.getList(a.key(), Tag.TAG_STRING)) s.add(t.getAsString());
            s.add(LEADER);
        }
        return r;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Rights r && r.table.equals(table);
    }

    @Override
    public int hashCode() {
        return table.hashCode();
    }

    @Override
    public String toString() {
        return table.toString();
    }
}

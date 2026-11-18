package works.repka.rysclaims.server;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import org.jetbrains.annotations.Nullable;
import works.repka.rysclaims.data.Chunks;
import works.repka.rysclaims.data.Claim;
import works.repka.rysclaims.data.ClaimData;
import works.repka.rysclaims.data.Limits;
import works.repka.rysclaims.data.PlayerBonus;
import works.repka.rysclaims.data.Plot;
import works.repka.rysclaims.data.Plots;
import works.repka.rysclaims.data.Rights;
import works.repka.rysclaims.data.Settings;
import works.repka.rysclaims.data.TeamLand;

/**
 * Вся логика земли без игры: занять, освободить с отсчётом, оставить, прогрузить, права. Клиенту не верим: каждое действие
 * проверяет право управления ({@link #PERM_CLAIM}, {@link #PERM_FORCE}, {@link #PERM_RIGHTS} через таблицу прав RYSTeams),
 * измерение и лимит. Оператор ({@code admin}) идёт мимо прав и лимитов, но не мимо чужой земли.
 *
 * <p>Измерение здесь строка id ({@code minecraft:overworld}), чанк упакован {@link Chunks#pack}. Время это миллисекунды от
 * эпохи из {@code clock}: отсчёт освобождения переживает перезапуск сервера.
 */
public final class ClaimService {
    public static final String PERM_CLAIM = "rysclaims:claim";
    public static final String PERM_FORCE = "rysclaims:force";
    public static final String PERM_RIGHTS = "rysclaims:rights";
    /** Расстояние в чанках, в котором машина на своей земле считается «рядом» с целью. */
    public static final int MACHINE_REACH = 2;

    /** Почему чанк пропущен. */
    public enum Skip {
        /** Нет права управления землёй в своей команде. */
        NO_RIGHT,
        /** Измерение без привата. */
        DIMENSION,
        /** Чанк уже чей-то другой. */
        OTHER_TEAM,
        /** Чанк уже свой / прогрузка уже в нужном положении / уже освобождается. */
        ALREADY,
        /** Не хватает лимита чанков. */
        LIMIT,
        /** Не хватает лимита прогрузки. */
        FORCE_LIMIT,
        /** Не рядом со своей землёй (настройка «Новый чанк только рядом со своим»). */
        NOT_ADJACENT,
        /** Чанк ничей. */
        NOT_CLAIMED,
        /** Чанк чужой: освобождать и прогружать нельзя. */
        NOT_OWNER,
        /** Чанк освобождается: прогрузку на нём не включить. */
        RELEASING,
        /** Сервер закрыл командам правку прав или закрепил переключатель. */
        LOCKED,
        /** Игрок или участок не найден. */
        NOT_FOUND
    }

    /** Итог действия: сколько сделано, сколько пропущено и почему. */
    public static final class Result {
        public int done;
        public final EnumMap<Skip, Integer> skipped = new EnumMap<>(Skip.class);
        public final LongList changed = new LongArrayList();

        void skip(Skip why) {
            skipped.merge(why, 1, Integer::sum);
        }

        void skip(Skip why, int n) {
            if (n > 0) skipped.merge(why, n, Integer::sum);
        }

        public int skippedTotal() {
            int n = 0;
            for (int v : skipped.values()) n += v;
            return n;
        }

        @Override
        public String toString() {
            return "done=" + done + " skipped=" + skipped;
        }
    }

    /** Что сервис сообщает наружу: слушателям контракта и прогрузке. Зовётся после правки данных. */
    public interface Hooks {
        default void claimed(UUID team, String dimension, LongList chunks) {}

        default void released(UUID team, String dimension, LongList chunks) {}

        /** У команды изменилось что-то, что влияет на прогрузку. */
        default void forceChanged(UUID team) {}

        /** Данные изменились: пора отметить файл для записи. */
        default void dirty() {}

        /** Что можно игрокам на чьей-то земле могло смениться: версия прав растёт, клиентам рассылка ({@code ClaimSync}). */
        default void accessChanged() {}

        /** Кто видит землю команды на карте сменилось: подписчикам регионы заново. */
        default void visibilityChanged(UUID team) {}

        /** Участки команды пересчитаны или переименованы; {@code events}: слияния и разрезы для ленты. */
        default void plotsChanged(UUID team, List<Plots.Event> events) {}

        /** Переключатели, доверенные или видимость земли команды сменились: окну участников новое состояние. */
        default void landChanged(UUID team) {}

        /** Игрок занял, освободил, включил или выключил прогрузку на {@code count} чанках своей команды (лента). */
        default void landEdited(UUID actor, UUID team, String op, int count) {}

        /** Неделя сверх лимита после слияния кончилась: лишние чанки пошли на освобождение. */
        default void overLimitReleased(UUID team, int count) {}
    }

    private final ClaimData data;
    private final Supplier<Settings> settings;
    private final Teams teams;
    private final LongSupplier clock;
    private final Hooks hooks;
    /** Освобождаемые чанки по измерениям: тик смотрит только их. */
    private final Map<String, LongSet> pending = new HashMap<>();
    /** Режим оператора: защита для этих игроков снята. Живёт до перезапуска. */
    private final Set<UUID> bypass = new HashSet<>();
    /** Последнее слияние на случай отмены: что ушло от команды. */
    private final Map<UUID, Merge> merges = new HashMap<>();

    private record Merge(UUID into, Map<String, LongList> chunks, int bonusClaims, int bonusForce, ClaimData.PlotSnapshot fromPlots,
                         ClaimData.PlotSnapshot intoPlots) {}

    public ClaimService(ClaimData data, Supplier<Settings> settings, Teams teams, LongSupplier clock, Hooks hooks) {
        this.data = data;
        this.settings = settings;
        this.teams = teams;
        this.clock = clock;
        this.hooks = hooks;
        for (String dim : data.dimensions()) {
            for (Long2ObjectMap.Entry<Claim> e : data.claims(dim).long2ObjectEntrySet()) {
                if (e.getValue().releasing()) pending.computeIfAbsent(dim, d -> new LongOpenHashSet()).add(e.getLongKey());
            }
        }
        rebuildPlots();
    }

    // ---- участки ----

    /**
     * Пересчёт всех участков без событий в ленту: файл 0.1 (номеров нет), переезд с FTB. Чанк без номера получает номер
     * соседей или новый участок без имени.
     */
    public void rebuildPlots() {
        Map<UUID, Set<String>> byTeam = new LinkedHashMap<>();
        for (String dim : data.dimensions()) {
            for (Claim c : data.claims(dim).values()) byTeam.computeIfAbsent(c.team(), t -> new HashSet<>()).add(dim);
        }
        for (Map.Entry<UUID, TeamLand> e : data.lands().entrySet()) {
            for (Plot p : e.getValue().plots.values()) byTeam.computeIfAbsent(e.getKey(), t -> new HashSet<>()).add(p.dim);
        }
        byTeam.forEach((team, dims) -> {
            for (String dim : dims) Plots.rebuild(data, team, dim);
        });
        hooks.dirty();
    }

    /** Пересчёт участков команды в измерении после перемены её земли; события уходят наружу. */
    private void plots(UUID team, String dim) {
        List<Plots.Event> events = Plots.rebuild(data, team, dim);
        hooks.plotsChanged(team, events);
    }

    public @Nullable Plot plot(UUID team, int id) {
        TeamLand land = data.land(team);
        return land == null ? null : land.plots.get(id);
    }

    /** Чанки участка. */
    public LongList plotChunks(UUID team, int id) {
        LongList out = new LongArrayList();
        Plot p = plot(team, id);
        if (p == null) return out;
        for (Long2ObjectMap.Entry<Claim> e : data.claims(p.dim).long2ObjectEntrySet()) {
            Claim c = e.getValue();
            if (c.plot() == id && c.team().equals(team)) out.add(e.getLongKey());
        }
        return out;
    }

    /** Участок чанка или null. */
    public @Nullable Plot plotAt(String dim, long chunk) {
        Claim c = data.get(dim, chunk);
        return c == null ? null : plot(c.team(), c.plot());
    }

    /** Переименовать участок (право «занимать и освобождать»). Пустое имя возвращает «Участок N». */
    public boolean renamePlot(@Nullable UUID actor, UUID team, int id, String name, boolean admin) {
        if (!admin && (actor == null || !manages(actor, team, PERM_CLAIM))) return false;
        Plot p = plot(team, id);
        if (p == null) return false;
        String n = cleanName(name);
        if (n.equals(p.name)) return true;
        p.name = n;
        hooks.plotsChanged(team, List.of());
        hooks.dirty();
        return true;
    }

    /** Имя участка: без управляющих знаков и кодов цвета, не длиннее {@link Plots#MAX_NAME}. */
    public static String cleanName(String name) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < name.length() && b.length() < Plots.MAX_NAME; i++) {
            char ch = name.charAt(i);
            if (ch == '§') {
                i++; // код цвета за знаком тоже
                continue;
            }
            if (ch < 32 || ch == 127) continue;
            b.append(ch);
        }
        return b.toString().trim();
    }

    public ClaimData data() {
        return data;
    }

    public Settings settings() {
        return settings.get();
    }

    public Teams teams() {
        return teams;
    }

    // ---- лимиты ----

    public Limits limits(UUID team) {
        return Limits.of(settings.get(), data.land(team), teams.members(team), teams.leader(team), data::bonus);
    }

    /** Добавка команде от оператора или награды: прибавляется к имеющейся. */
    public void addBonus(UUID team, int claims, int force) {
        TeamLand land = data.landOrCreate(team);
        land.bonusClaims += claims;
        land.bonusForce += force;
        hooks.forceChanged(team);
        hooks.dirty();
    }

    /** Личная добавка игрока: прибавляется к имеющейся. */
    public void addPlayerBonus(UUID player, int claims, int force) {
        var b = data.bonusOrCreate(player);
        b.claims += claims;
        b.force += force;
        hooks.forceChanged(teams.teamOf(player));
        hooks.dirty();
    }

    // ---- занять, освободить, прогрузить ----

    /**
     * Занимает чанки по порядку списка, пока хватает лимита: при нехватке занимается начало списка (квадрат идёт от
     * центра). {@code actor} null значит консоль или оператор от имени команды.
     */
    public Result claim(@Nullable UUID actor, UUID team, String dim, LongList chunks, boolean admin) {
        Result r = new Result();
        if (!admin && (actor == null || !manages(actor, team, PERM_CLAIM))) {
            r.skip(Skip.NO_RIGHT, chunks.size());
            return r;
        }
        Settings s = settings.get();
        if (!s.claimable(dim)) {
            r.skip(Skip.DIMENSION, chunks.size());
            return r;
        }
        int limit = limits(team).claims;
        int have = data.claimed(team);
        boolean adjacent = s.adjacentOnly && !admin;
        boolean any = adjacent && hasLand(team, dim);
        long now = clock.getAsLong();
        LongList todo = new LongArrayList(chunks);
        boolean progress = true;
        while (!todo.isEmpty() && progress) {
            progress = false;
            LongList later = new LongArrayList();
            for (int i = 0; i < todo.size(); i++) {
                long chunk = todo.getLong(i);
                if (Chunks.sablePlot(chunk)) {
                    r.skip(Skip.DIMENSION);
                    continue;
                }
                Claim c = data.get(dim, chunk);
                if (c != null) {
                    if (!c.team().equals(team)) r.skip(Skip.OTHER_TEAM);
                    else if (c.releasing()) {
                        keepOne(dim, chunk, c);
                        r.done++;
                    } else r.skip(Skip.ALREADY);
                    continue;
                }
                if (!admin && have >= limit) {
                    r.skip(Skip.LIMIT);
                    continue;
                }
                if (adjacent && any && !touches(team, dim, chunk)) {
                    later.add(chunk);
                    continue;
                }
                data.put(dim, chunk, team, actor, now);
                any = true;
                have++;
                r.done++;
                r.changed.add(chunk);
                progress = true;
            }
            todo = later;
        }
        r.skip(Skip.NOT_ADJACENT, todo.size());
        if (r.done > 0) {
            data.edited();
            hooks.dirty();
        }
        if (!r.changed.isEmpty()) {
            plots(team, dim);
            hooks.claimed(team, dim, r.changed);
            if (actor != null) hooks.landEdited(actor, team, "claim", r.changed.size());
        }
        return r;
    }

    /** Освобождает с отсчётом: до его конца чанки защищены, прогружены и входят в лимит. */
    public Result release(@Nullable UUID actor, UUID team, String dim, LongList chunks, boolean admin) {
        Result r = new Result();
        if (!admin && (actor == null || !manages(actor, team, PERM_CLAIM))) {
            r.skip(Skip.NO_RIGHT, chunks.size());
            return r;
        }
        int seconds = settings.get().releaseSeconds;
        long at = clock.getAsLong() + seconds * 1000L;
        LongList now = new LongArrayList();
        for (int i = 0; i < chunks.size(); i++) {
            long chunk = chunks.getLong(i);
            Claim c = data.get(dim, chunk);
            if (c == null) r.skip(Skip.NOT_CLAIMED);
            else if (!c.team().equals(team)) r.skip(Skip.NOT_OWNER);
            else if (c.releasing()) r.skip(Skip.ALREADY);
            else {
                r.done++;
                r.changed.add(chunk);
                if (seconds <= 0) now.add(chunk);
                else {
                    data.setRelease(c, at);
                    pending.computeIfAbsent(dim, d -> new LongOpenHashSet()).add(chunk);
                }
            }
        }
        if (!now.isEmpty()) drop(dim, now);
        if (r.done > 0) {
            data.edited();
            hooks.dirty();
            if (actor != null) hooks.landEdited(actor, team, "release", r.done);
        }
        return r;
    }

    /** Отменяет все идущие освобождения команды. Возвращает число оставленных чанков. */
    public int keep(@Nullable UUID actor, UUID team, boolean admin) {
        if (!admin && (actor == null || !manages(actor, team, PERM_CLAIM))) return -1;
        int n = 0;
        for (Map.Entry<String, LongSet> e : pending.entrySet()) {
            LongList mine = new LongArrayList();
            for (long chunk : e.getValue()) {
                Claim c = data.get(e.getKey(), chunk);
                if (c != null && c.team().equals(team)) mine.add(chunk);
            }
            for (long chunk : mine) {
                keepOne(e.getKey(), chunk, data.get(e.getKey(), chunk));
                n++;
            }
        }
        pending.values().removeIf(LongSet::isEmpty);
        if (n > 0) hooks.dirty();
        return n;
    }

    private void keepOne(String dim, long chunk, Claim c) {
        data.setRelease(c, 0);
        LongSet set = pending.get(dim);
        if (set != null) set.remove(chunk);
    }

    /** Включает или выключает прогрузку. */
    public Result load(@Nullable UUID actor, UUID team, String dim, LongList chunks, boolean on, boolean admin) {
        Result r = new Result();
        if (!admin && (actor == null || !manages(actor, team, PERM_FORCE))) {
            r.skip(Skip.NO_RIGHT, chunks.size());
            return r;
        }
        int limit = limits(team).force;
        for (int i = 0; i < chunks.size(); i++) {
            long chunk = chunks.getLong(i);
            Claim c = data.get(dim, chunk);
            if (c == null) r.skip(Skip.NOT_CLAIMED);
            else if (!c.team().equals(team)) r.skip(Skip.NOT_OWNER);
            else if (c.force() == on) r.skip(Skip.ALREADY);
            else if (on && c.releasing()) r.skip(Skip.RELEASING);
            else if (on && !admin && data.forced(team) >= limit) r.skip(Skip.FORCE_LIMIT);
            else {
                data.setForce(c, on);
                r.done++;
                r.changed.add(chunk);
            }
        }
        if (r.done > 0) {
            data.edited();
            hooks.forceChanged(team);
            hooks.dirty();
            if (actor != null) hooks.landEdited(actor, team, on ? "load" : "unload", r.done);
        }
        return r;
    }

    /** Завершает освобождения, у которых вышел срок. Зовётся раз в секунду. */
    public void tick() {
        if (pending.isEmpty()) return;
        long now = clock.getAsLong();
        for (Map.Entry<String, LongSet> e : new LinkedHashMap<>(pending).entrySet()) {
            LongList due = new LongArrayList();
            for (long chunk : e.getValue()) {
                Claim c = data.get(e.getKey(), chunk);
                if (c == null || !c.releasing() || c.releaseAt() <= now) due.add(chunk);
            }
            if (due.isEmpty()) continue;
            e.getValue().removeAll(due);
            drop(e.getKey(), due);
        }
        pending.values().removeIf(LongSet::isEmpty);
    }

    /** Сколько чанков команды сейчас освобождается и когда освободится первый. */
    public long[] releasing(UUID team) {
        long count = 0, first = Long.MAX_VALUE;
        for (Map.Entry<String, LongSet> e : pending.entrySet()) {
            for (long chunk : e.getValue()) {
                Claim c = data.get(e.getKey(), chunk);
                if (c == null || !c.team().equals(team)) continue;
                count++;
                first = Math.min(first, c.releaseAt());
            }
        }
        return new long[] {count, count == 0 ? 0 : first};
    }

    private void drop(String dim, LongList chunks) {
        Map<UUID, LongList> byTeam = new LinkedHashMap<>();
        Set<UUID> unforced = new HashSet<>();
        for (int i = 0; i < chunks.size(); i++) {
            long chunk = chunks.getLong(i);
            Claim c = data.remove(dim, chunk);
            if (c == null) continue;
            if (c.force()) unforced.add(c.team());
            byTeam.computeIfAbsent(c.team(), t -> new LongArrayList()).add(chunk);
        }
        for (UUID team : unforced) hooks.forceChanged(team);
        byTeam.forEach((team, list) -> {
            plots(team, dim);
            hooks.released(team, dim, list);
        });
        if (!byTeam.isEmpty()) hooks.dirty();
    }

    private boolean hasLand(UUID team, String dim) {
        for (Claim c : data.claims(dim).values()) if (c.team().equals(team)) return true;
        return false;
    }

    private boolean touches(UUID team, String dim, long chunk) {
        for (long side : Chunks.sides(chunk)) if (team.equals(data.owner(dim, side))) return true;
        return false;
    }

    // ---- права ----

    /** Может ли игрок управлять землёй команды: он в ней и у его роли есть право. */
    public boolean manages(UUID actor, UUID team, String permission) {
        return teams.teamOf(actor).equals(team) && teams.can(actor, permission);
    }

    /** Права и переключатели можно править: сервер это позволяет и у игрока есть право. */
    public boolean editsRights(@Nullable UUID actor, UUID team, boolean admin) {
        if (admin) return true;
        return actor != null && settings.get().teamsEditRights && manages(actor, team, PERM_RIGHTS);
    }

    public boolean bypass(UUID player) {
        return bypass.contains(player);
    }

    /** Кто сейчас в режиме оператора. */
    public java.util.List<UUID> bypassers() {
        return new java.util.ArrayList<>(bypass);
    }

    /** Снимает режим оператора (op сняли, игрок вышел). */
    public void clearBypass(UUID player) {
        if (bypass.remove(player)) hooks.accessChanged();
    }

    /** Переключает режим оператора; возвращает новое состояние. */
    public boolean toggleBypass(UUID player) {
        boolean on = !bypass.remove(player);
        if (on) bypass.add(player);
        hooks.accessChanged();
        return on;
    }

    // ---- правка прав, переключателей, доверенных (окно и команды, проверка права у вызывающего) ----

    private void accessEdited(UUID team) {
        hooks.accessChanged();
        hooks.landChanged(team);
        hooks.dirty();
    }

    /**
     * Правка прав команды: версия прав и рассылка только если таблица на самом деле изменилась (перепроверка 18.11, пункт
     * 7: повтор того же набора не будит всех игроков).
     */
    private void editRights(UUID team, java.util.function.Consumer<Rights> edit) {
        Rights r = data.landOrCreate(team).rights;
        int before = r.bits();
        edit.accept(r);
        if (r.bits() != before) accessEdited(team);
    }

    public void applyPreset(UUID team, Rights.Preset preset) {
        editRights(team, r -> r.apply(preset));
    }

    /** Клетка окна «Права» по столбцу 0.1 (команда, союзники, все), с монотонностью. */
    public void toggleRight(UUID team, Rights.Action a, Rights.Column c, boolean on) {
        editRights(team, r -> r.toggle(a, c, on));
    }

    /** Клетка окна по столбцу роли ({@code Rights.COLUMNS}), с монотонностью. */
    public void toggleRole(UUID team, Rights.Action a, String column, boolean on) {
        editRights(team, r -> r.toggleRole(a, column, on));
    }

    /** Отметка по столбцу таблицы ({@code Rights.COLUMNS} или {@code team}), без монотонности. */
    public void setRight(UUID team, Rights.Action a, String column, boolean on) {
        editRights(team, r -> r.set(a, column, on));
    }

    public void setRights(UUID team, Rights rights) {
        editRights(team, r -> r.copyFrom(rights));
    }

    /** Меняет переключатель; закреплённый сервером не меняется (false). */
    public boolean setFlag(UUID team, TeamLand.Flag f, boolean on) {
        if (settings.get().lockedFlags.containsKey(f)) return false;
        data.landOrCreate(team).flag(f, on);
        accessEdited(team);
        return true;
    }

    /** Действующий переключатель земли команды: закреплённый сервером, иначе свой, иначе по умолчанию. */
    public boolean flag(@Nullable UUID owner, TeamLand.Flag f) {
        Boolean locked = settings.get().lockedFlags.get(f);
        if (locked != null) return locked;
        TeamLand land = owner == null ? null : data.land(owner);
        return land == null ? f.def : land.flag(f);
    }

    public void setVisibility(UUID team, TeamLand.Visibility v) {
        TeamLand land = data.landOrCreate(team);
        if (land.visibility == v) return;
        land.visibility = v;
        hooks.visibilityChanged(team);
        hooks.landChanged(team);
        hooks.dirty();
    }

    /** Настройки журнала команды: лента и тост. */
    public void setLog(UUID team, boolean feed, boolean toast) {
        TeamLand land = data.landOrCreate(team);
        land.logFeed = feed;
        land.logToast = toast;
        hooks.landChanged(team);
        hooks.dirty();
    }

    /** Доверенный игрок: права столбца «Союзники». Возвращает, изменился ли список. */
    public boolean trust(UUID team, UUID player, boolean on) {
        TeamLand land = data.landOrCreate(team);
        boolean changed = on ? land.trusted.add(player) : land.trusted.remove(player);
        if (changed) accessEdited(team);
        return changed;
    }

    /** Кем игрок приходится хозяину земли. */
    public Rights.Relation relation(UUID player, UUID owner) {
        if (player.equals(owner) || teams.teamOf(player).equals(owner)) return Rights.Relation.TEAM;
        TeamLand land = data.land(owner);
        if (land != null && land.legacyMembers.contains(player)) return Rights.Relation.TEAM;
        Settings s = settings.get();
        if (s.allyMode == Settings.AllyMode.ALL) return Rights.Relation.ALLY;
        if (s.allyMode == Settings.AllyMode.NONE) return Rights.Relation.OTHER;
        if (land != null && land.trusted.contains(player)) return Rights.Relation.ALLY;
        if (teams.allies(owner).contains(teams.teamOf(player))) return Rights.Relation.ALLY;
        return Rights.Relation.OTHER;
    }

    /** Может ли игрок сделать действие в чанке. Одна проверка для событий защиты и для контракта ядра. */
    public boolean can(UUID player, String dim, long chunk, Rights.Action action) {
        Settings s = settings.get();
        if (!s.protection || bypass.contains(player)) return true;
        UUID owner = data.owner(dim, chunk);
        if (owner == null) return !s.wildernessClosed(dim);
        return allows(player, owner, action);
    }

    /** Может ли игрок сделать действие на земле команды {@code owner}: то же, что {@link #can}, без поиска чанка (окно, подсказка). */
    public boolean allows(UUID player, UUID owner, Rights.Action action) {
        Settings s = settings.get();
        if (!s.protection || bypass.contains(player)) return true;
        Rights.Relation rel = relation(player, owner);
        TeamLand land = data.land(owner);
        Rights rights = land == null ? s.defaultRights : land.rights;
        String role = null;
        if (rel == Rights.Relation.TEAM && !player.equals(owner)) {
            if (land != null && land.legacyMembers.contains(player)) role = player.equals(land.legacyOwner) ? Rights.LEADER : Rights.MEMBER;
            else role = teams.roleOf(player);
        }
        return rights.allows(action, rel, role);
    }

    /**
     * Может ли машина действовать (план, раздел 11, «Машины»): профиль участника с его правами, затем место (машина на
     * земле той же команды не дальше {@link #MACHINE_REACH} чанков), иначе переключатель «Машины снаружи».
     *
     * @param from чанк машины или {@code Long.MIN_VALUE}, если место неизвестно
     */
    public boolean canMachine(String dim, long from, @Nullable UUID profile, long target, Rights.Action action) {
        return canMachine(dim, from, profile, target, action, MACHINE_REACH);
    }

    /**
     * То же с дальностью «рядом» в чанках. Машинам с дальним действием (лазер Oritech, постройка RFTools, пространственный
     * ввод-вывод AE2) место берётся из блок-сущности, ему можно верить на любом расстоянии: обе точки на земле одной команды.
     */
    public boolean canMachine(String dim, long from, @Nullable UUID profile, long target, Rights.Action action, int reach) {
        Settings s = settings.get();
        if (!s.protection) return true;
        UUID owner = data.owner(dim, target);
        if (owner == null) return true;
        TeamLand land = data.land(owner);
        if (profile != null && flag(owner, TeamLand.Flag.MACHINES_PROFILE) && can(profile, dim, target, action)) {
            return true;
        }
        if (from != Long.MIN_VALUE && owner.equals(data.owner(dim, from))
            && Math.abs(Chunks.x(from) - Chunks.x(target)) <= reach
            && Math.abs(Chunks.z(from) - Chunks.z(target)) <= reach) {
            return flag(owner, TeamLand.Flag.MACHINES_INSIDE);
        }
        return flag(owner, TeamLand.Flag.MACHINES_OUTSIDE);
    }

    /** Почему отказано: строка у прицела и в журнале (план, раздел 11, «Что видит игрок при отказе»). */
    public enum Reason {
        BUILD, OPEN, USE, ENTITY, FLUID, PVP,
        /** Своя команда, но у роли нет права. */
        GUEST,
        /** Свободная земля закрыта сервером. */
        WILDERNESS,
        /** Машина: профиль не участник, место неизвестно или снаружи. */
        MACHINE,
        /** Взрыв не тронул блоки земли (только журнал). */
        EXPLOSION,
        /** Оператор правил землю команды в режиме оператора (только журнал). */
        OPERATOR,
        /** Хозяин могилы сломал её на чужой земле (только журнал). */
        GRAVE,
        /** Разборка постройки положила бы блоки на землю, закрытую ей: постройка остаётся собранной. */
        DISASSEMBLE;

        public String key() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }

        public static Reason of(Rights.Action a) {
            return switch (a) {
                case BUILD -> BUILD;
                case OPEN -> OPEN;
                case USE -> USE;
                case ENTITY -> ENTITY;
            };
        }

        public static @Nullable Reason parse(String key) {
            for (Reason r : values()) if (r.key().equals(key)) return r;
            return null;
        }
    }

    /** Причина отказа игроку в чанке (зовётся только после отказа {@link #can}). */
    public Reason reason(UUID player, String dim, long chunk, Rights.Action action) {
        UUID owner = data.owner(dim, chunk);
        if (owner == null) return Reason.WILDERNESS;
        if (relation(player, owner) == Rights.Relation.TEAM) return Reason.GUEST;
        return Reason.of(action);
    }

    /**
     * Может ли жидкость, огонь или поршень перейти из чанка {@code from} в чанк {@code to}: в тот же чанк и на ничью землю
     * всегда, на землю другой команды (в том числе со свободной) только если сервер это разрешил ({@code allowed}).
     */
    public boolean mayCross(String dim, long from, long to, boolean allowed) {
        if (from == to || allowed || !settings.get().protection) return true;
        UUID target = data.owner(dim, to);
        return target == null || target.equals(data.owner(dim, from));
    }

    /**
     * Предмет из {@code from} в {@code to} (воронка, выбрасыватель): как {@link #mayCross}, но со свободной земли можно и на
     * землю команды (разбор скептика 18.11, риск 8): цепочка воронок от фермы на свободной земле к своей базе не встаёт
     * молча. Положить предмет в чужой сундук не опаснее, чем бросить его рядом; между землями разных команд закрыто.
     */
    public boolean mayInsert(String dim, long from, long to, boolean allowed) {
        if (from == to || allowed || !settings.get().protection) return true;
        UUID target = data.owner(dim, to);
        if (target == null) return true;
        UUID source = data.owner(dim, from);
        return source == null || target.equals(source);
    }

    /**
     * Взрыв без хозяина ломает блок чанка {@code chunk}, если его центр в чанке {@code center}: ничья земля всегда; иначе по
     * настройке сервера, при «свои» только земля той же команды, что центр, и с переключателем «Свои взрывы ломают блоки».
     */
    public boolean ownerlessExplosion(String dim, long center, long chunk) {
        Settings s = settings.get();
        if (!s.protection) return true;
        UUID owner = data.owner(dim, chunk);
        if (owner == null) return true;
        return switch (s.ownerless) {
            case ALWAYS -> true;
            case NEVER -> false;
            case OWN -> {
                yield owner.equals(data.owner(dim, center)) && flag(owner, TeamLand.Flag.OWN_EXPLOSIONS);
            }
        };
    }

    // ---- события команд ----

    /** Сколько живёт отсчёт освобождения земли распавшейся команды и лишней земли после слияния, и запись архива. */
    public static final long WEEK_MS = 7L * 24 * 3600 * 1000;
    /** Сколько после ухода можно забрать свои чанки. */
    public static final long TAKE_MS = 3600_000L;

    /**
     * Отдать личную землю команде (ответ «Отдать» на вопрос {@code 45}, {@code /claims personal give}): переходит вся, если
     * хватает лимита команды, иначе остаётся личной.
     */
    public boolean joined(UUID team, UUID player) {
        if (team.equals(player) || data.claimed(player) == 0) return false;
        if (data.claimed(team) + data.claimed(player) > limits(team).claims) return false;
        var moved = data.transfer(player, team);
        // только эти чанки игрок может забрать при уходе
        for (Map.Entry<String, LongList> e : moved.entrySet()) for (long chunk : e.getValue()) data.brought(e.getKey(), chunk, true);
        PlayerBonus b = data.bonus(player);
        if (b != null) b.keepPersonal = false;
        after(player, team, moved);
        return true;
    }

    /** Нужен ли игроку вопрос о личной земле (макет 45): он в команде, у него есть личная земля, и он не сказал «Оставить». */
    public boolean personalPending(UUID player) {
        if (teams.teamOf(player).equals(player) || data.claimed(player) == 0) return false;
        PlayerBonus b = data.bonus(player);
        return b == null || !b.keepPersonal;
    }

    /** «Оставить себе»: земля остаётся личной и закрыта для правок, пока игрок в команде. */
    public void keepPersonal(UUID player) {
        data.bonusOrCreate(player).keepPersonal = true;
        hooks.dirty();
    }

    /**
     * Чанки команды, которые игрок принёс из личной земли (пометка {@code brought}, перепроверка 18.11, пункт 1): их можно
     * забрать при уходе (план, раздел 8). Занятое им для команды (поле {@code by}) остаётся команде.
     */
    public Map<String, LongList> broughtBy(UUID team, UUID player) {
        Map<String, LongList> out = new LinkedHashMap<>();
        for (String dim : data.dimensions()) {
            for (Long2ObjectMap.Entry<Claim> e : data.claims(dim).long2ObjectEntrySet()) {
                Claim c = e.getValue();
                if (c.team().equals(team) && c.brought() && player.equals(c.by()) && !c.releasing()) out.computeIfAbsent(dim, d -> new LongArrayList()).add(e.getLongKey());
            }
        }
        return out;
    }

    /**
     * «Забрать свои чанки»: ушедший игрок забирает у бывшей команды чанки, которые принёс сам, в пределах своего личного
     * лимита. Возвращает, сколько забрано.
     */
    public int takeBack(UUID player, UUID fromTeam) {
        if (fromTeam.equals(player) || teams.teamOf(player).equals(fromTeam)) return 0;
        UUID to = teams.teamOf(player);
        int room = limits(to).claims - data.claimed(to);
        int n = 0;
        Map<String, LongList> moved = new LinkedHashMap<>();
        for (Map.Entry<String, LongList> e : broughtBy(fromTeam, player).entrySet()) {
            for (long chunk : e.getValue()) {
                if (n >= room) break;
                data.reassign(e.getKey(), chunk, to);
                // номер участка бывшей команды новой не годится (перепроверка 18.11, пункт 12)
                data.clearPlot(e.getKey(), chunk);
                // в личную землю чанк вернулся: снова личный, не «принесённый»
                if (to.equals(player)) data.brought(e.getKey(), chunk, false);
                moved.computeIfAbsent(e.getKey(), d -> new LongArrayList()).add(chunk);
                n++;
            }
        }
        if (n > 0) {
            data.edited();
            after(fromTeam, to, moved);
        }
        return n;
    }

    /** Строка предпросмотра слияния (план, раздел 8): сколько земли перейдёт и хватит ли лимита. Второе число: нехватка. */
    public int[] mergePreview(UUID from, UUID into) {
        int n = data.claimed(from);
        int over = data.claimed(into) + n - limits(into).claims;
        return new int[] {n, Math.max(0, over)};
    }

    /**
     * Распад (план, раздел 8): вся земля освобождается с отсчётом 7 дней, защита и лимит держатся до конца, запись в архиве
     * на те же 7 дней. Управлять такой землёй может только оператор.
     */
    public void disbanded(UUID team, String name) {
        long at = clock.getAsLong();
        int n = 0;
        for (String dim : data.dimensions()) {
            for (Long2ObjectMap.Entry<Claim> e : data.claims(dim).long2ObjectEntrySet()) {
                Claim c = e.getValue();
                if (!c.team().equals(team) || c.releasing()) continue;
                data.setRelease(c, at + WEEK_MS);
                pending.computeIfAbsent(dim, d -> new LongOpenHashSet()).add(e.getLongKey());
                n++;
            }
        }
        if (n == 0) return;
        net.minecraft.nbt.CompoundTag entry = new net.minecraft.nbt.CompoundTag();
        entry.putUUID("team", team);
        entry.putString("name", name);
        entry.putLong("at", at);
        entry.putInt("chunks", n);
        data.archive(entry);
        hooks.forceChanged(team);
        hooks.dirty();
    }

    /**
     * Команда вернулась из архива RYSTeams (тот же UUID, событие только {@code onJoined}, перепроверка 18.11, пункт 2):
     * отсчёт распада снимается с чанков, которым его поставил {@link #disbanded} (срок ровно «распад + 7 дней»), запись
     * архива уходит. Чанки, которые до распада освобождал сам игрок, продолжают освобождаться. Возвращает, сколько оставлено.
     */
    public int restored(UUID team) {
        long at = -1;
        for (int i = 0; i < data.archive().size(); i++) {
            if (data.archive().get(i) instanceof net.minecraft.nbt.CompoundTag t && t.hasUUID("team") && t.getUUID("team").equals(team)) {
                at = Math.max(at, t.getLong("at"));
            }
        }
        if (at < 0) return 0;
        long releaseAt = at + WEEK_MS;
        int n = 0;
        for (String dim : data.dimensions()) {
            for (Long2ObjectMap.Entry<Claim> e : data.claims(dim).long2ObjectEntrySet()) {
                Claim c = e.getValue();
                if (!c.team().equals(team) || c.releaseAt() != releaseAt) continue;
                keepOne(dim, e.getLongKey(), c);
                n++;
            }
        }
        data.archive().removeIf(x -> x instanceof net.minecraft.nbt.CompoundTag t && t.hasUUID("team") && t.getUUID("team").equals(team));
        hooks.forceChanged(team);
        hooks.dirty();
        return n;
    }

    /**
     * Раз в минуту: земля больше лимита после слияния держится 7 дней, потом лишнее освобождается с обычным отсчётом,
     * сначала самые новые чанки без прогрузки, в ленте строка. Отсчёт ставит только {@link #merged} (перепроверка 18.11,
     * пункт 3): сниженный оператором лимит или ушедший участник землю не отнимают, команда просто не может занимать новое.
     * Архив чистится от записей старше 7 дней.
     */
    public void overLimitTick() {
        long now = clock.getAsLong();
        if (data.pruneArchive(now - WEEK_MS)) hooks.dirty();
        for (TeamLand land : data.lands().values()) {
            if (land.claimed() == 0 && land.overSince == 0) continue;
            int limit = limits(land.id).claims;
            int over = land.claimed() - limit;
            if (over <= 0) {
                if (land.overSince != 0) {
                    land.overSince = 0;
                    hooks.dirty();
                }
                continue;
            }
            if (land.overSince == 0 || now - land.overSince < WEEK_MS) continue;
            int total = over;
            for (String dim : data.dimensions()) {
                java.util.List<Claim> mine = new java.util.ArrayList<>();
                for (Claim c : data.claims(dim).values()) if (c.team().equals(land.id) && !c.releasing()) mine.add(c);
                mine.sort((a, b) -> a.force() != b.force() ? Boolean.compare(a.force(), b.force()) : Long.compare(b.at(), a.at()));
                LongList pick = new LongArrayList();
                for (int i = 0; i < mine.size() && pick.size() < over; i++) pick.add(mine.get(i).pos());
                over -= pick.size();
                if (!pick.isEmpty()) release(null, land.id, dim, pick, true);
                if (over <= 0) break;
            }
            land.overSince = 0;
            hooks.overLimitReleased(land.id, total - Math.max(0, over));
            hooks.dirty();
        }
    }

    /** Слияние: вся земля и добавка уходящей команды переходит принимающей. Отмена в течение 30 секунд возвращает. */
    public void merged(UUID from, UUID into) {
        TeamLand src = data.land(from);
        int bc = src == null ? 0 : src.bonusClaims, bf = src == null ? 0 : src.bonusForce;
        ClaimData.PlotSnapshot fromPlots = data.plotSnapshot(from), intoPlots = data.plotSnapshot(into);
        var moved = data.transfer(from, into);
        merges.put(from, new Merge(into, moved, bc, bf, fromPlots, intoPlots));
        TeamLand dst = data.landOrCreate(into);
        if (dst.claimed() > limits(into).claims && dst.overSince == 0) dst.overSince = clock.getAsLong();
        after(from, into, moved);
    }

    public void mergeUndone(UUID from, UUID into) {
        Merge m = merges.remove(from);
        if (m == null || !m.into.equals(into)) return;
        Map<String, LongList> back = new LinkedHashMap<>();
        for (Map.Entry<String, LongList> e : m.chunks.entrySet()) {
            for (long chunk : e.getValue()) {
                // чанк, который за эти 30 секунд освободили и заняла третья команда, остаётся у неё (перепроверка 18.11, пункт 11)
                if (!into.equals(data.owner(e.getKey(), chunk))) continue;
                data.reassign(e.getKey(), chunk, from);
                back.computeIfAbsent(e.getKey(), d -> new LongArrayList()).add(chunk);
            }
        }
        // участки обеих команд как до слияния: номера и имена (перепроверка 18.11, пункт 12)
        data.landOrCreate(into).plots.keySet().removeIf(id -> !m.intoPlots.plots().containsKey(id) && isTransferredPlot(m, id));
        data.restorePlots(from, m.fromPlots);
        data.restorePlots(into, m.intoPlots);
        TeamLand dst = data.landOrCreate(into), src = data.landOrCreate(from);
        dst.bonusClaims -= m.bonusClaims;
        dst.bonusForce -= m.bonusForce;
        src.bonusClaims += m.bonusClaims;
        src.bonusForce += m.bonusForce;
        after(into, from, back);
        // пересчёт принимающей и в измерениях, откуда ничего не вернулось: её участки тоже вернулись
        for (String dim : m.intoPlots.chunks().keySet()) if (!back.containsKey(dim)) plots(into, dim);
    }

    /** Участок принимающей, появившийся после слияния (номер не меньше снимка): это перенесённые участки уходящей, их убираем. */
    private static boolean isTransferredPlot(Merge m, int id) {
        return id >= m.intoPlots.nextPlot();
    }

    private void after(UUID from, UUID to, Map<String, LongList> moved) {
        moved.forEach((dim, list) -> {
            plots(from, dim);
            plots(to, dim);
            hooks.released(from, dim, list);
            hooks.claimed(to, dim, list);
        });
        hooks.forceChanged(from);
        hooks.forceChanged(to);
        hooks.dirty();
    }
}

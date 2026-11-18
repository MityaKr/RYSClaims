package works.repka.rysclaims.server;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
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
        /** Сервер закрыл командам правку прав. */
        LOCKED
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

    private record Merge(UUID into, Map<String, LongList> chunks, int bonusClaims, int bonusForce) {}

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
        if (!r.changed.isEmpty()) hooks.claimed(team, dim, r.changed);
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
        byTeam.forEach((team, list) -> hooks.released(team, dim, list));
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

    /** Переключает режим оператора; возвращает новое состояние. */
    public boolean toggleBypass(UUID player) {
        if (!bypass.remove(player)) {
            bypass.add(player);
            return true;
        }
        return false;
    }

    /** Кем игрок приходится хозяину земли. */
    public Rights.Relation relation(UUID player, UUID owner) {
        if (player.equals(owner) || teams.teamOf(player).equals(owner)) return Rights.Relation.TEAM;
        Settings s = settings.get();
        if (s.allyMode == Settings.AllyMode.ALL) return Rights.Relation.ALLY;
        if (s.allyMode == Settings.AllyMode.NONE) return Rights.Relation.OTHER;
        TeamLand land = data.land(owner);
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
        Rights.Relation rel = relation(player, owner);
        TeamLand land = data.land(owner);
        Rights rights = land == null ? s.defaultRights : land.rights;
        String role = rel == Rights.Relation.TEAM && !player.equals(owner) ? teams.roleOf(player) : null;
        return rights.allows(action, rel, role);
    }

    /**
     * Может ли машина действовать (план, раздел 11, «Машины»): профиль участника с его правами, затем место (машина на
     * земле той же команды не дальше {@link #MACHINE_REACH} чанков), иначе переключатель «Машины снаружи».
     *
     * @param from чанк машины или {@code Long.MIN_VALUE}, если место неизвестно
     */
    public boolean canMachine(String dim, long from, @Nullable UUID profile, long target, Rights.Action action) {
        Settings s = settings.get();
        if (!s.protection) return true;
        UUID owner = data.owner(dim, target);
        if (owner == null) return true;
        TeamLand land = data.land(owner);
        if (profile != null && (land == null || land.flag(TeamLand.Flag.MACHINES_PROFILE)) && can(profile, dim, target, action)) {
            return true;
        }
        if (from != Long.MIN_VALUE && owner.equals(data.owner(dim, from))
            && Math.abs(Chunks.x(from) - Chunks.x(target)) <= MACHINE_REACH
            && Math.abs(Chunks.z(from) - Chunks.z(target)) <= MACHINE_REACH) {
            return land == null || land.flag(TeamLand.Flag.MACHINES_INSIDE);
        }
        return land != null && land.flag(TeamLand.Flag.MACHINES_OUTSIDE);
    }

    // ---- события команд ----

    /** Игрок вступил: его личная земля переходит команде, если хватает лимита (0.1), иначе остаётся личной. */
    public boolean joined(UUID team, UUID player) {
        if (team.equals(player) || data.claimed(player) == 0) return false;
        if (data.claimed(team) + data.claimed(player) > limits(team).claims) return false;
        var moved = data.transfer(player, team);
        after(player, team, moved);
        return true;
    }

    /** Слияние: вся земля и добавка уходящей команды переходит принимающей. Отмена в течение 30 секунд возвращает. */
    public void merged(UUID from, UUID into) {
        TeamLand src = data.land(from);
        int bc = src == null ? 0 : src.bonusClaims, bf = src == null ? 0 : src.bonusForce;
        var moved = data.transfer(from, into);
        merges.put(from, new Merge(into, moved, bc, bf));
        after(from, into, moved);
    }

    public void mergeUndone(UUID from, UUID into) {
        Merge m = merges.remove(from);
        if (m == null || !m.into.equals(into)) return;
        for (Map.Entry<String, LongList> e : m.chunks.entrySet()) {
            for (long chunk : e.getValue()) data.reassign(e.getKey(), chunk, from);
        }
        TeamLand dst = data.landOrCreate(into), src = data.landOrCreate(from);
        dst.bonusClaims -= m.bonusClaims;
        dst.bonusForce -= m.bonusForce;
        src.bonusClaims += m.bonusClaims;
        src.bonusForce += m.bonusForce;
        after(into, from, m.chunks);
    }

    private void after(UUID from, UUID to, Map<String, LongList> moved) {
        moved.forEach((dim, list) -> {
            hooks.released(from, dim, list);
            hooks.claimed(to, dim, list);
        });
        hooks.forceChanged(from);
        hooks.forceChanged(to);
        hooks.dirty();
    }
}

package works.repka.rysclaims.data;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMaps;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongList;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import org.jetbrains.annotations.Nullable;

/**
 * Вся земля мира в памяти: на измерение {@link Long2ObjectOpenHashMap} чанков, на команду её запись и счётчики, личные
 * добавки игроков. Без игры: файл и {@code SavedData} это {@code ClaimSavedData}, сюда приходит только {@link CompoundTag}.
 *
 * <p>Формат {@code rysclaims.dat} (план, раздел 9.2), {@code v: 1}:
 * <pre>
 * v, edits
 * ids: [uuid…]                       команды по номерам, на них ссылается поле team у чанка
 * teams: { uuid: { bonusClaims, bonusForce, lastSeen, loadMode, loadHours, visibility, rights, flags, trusted,
 *                  machineNames, legacy?, plots: [ { id, name, dim } ], nextPlot } }
 * claims: { измерение: [ { pos, team, by?, at, plot, force, releaseAt } ] }
 * players: { uuid: { bonusClaims, bonusForce } }
 * archive: [ … ]                     распавшиеся команды (0.2), хранится как есть
 * </pre>
 */
public final class ClaimData {
    public static final int VERSION = 1;

    private final Map<String, Long2ObjectOpenHashMap<Claim>> dims = new LinkedHashMap<>();
    private final Map<UUID, TeamLand> teams = new LinkedHashMap<>();
    private final Map<UUID, PlayerBonus> players = new LinkedHashMap<>();
    private ListTag archive = new ListTag();
    /** «Дом» летающей постройки Sable: UUID подуровня → команда земли, где её собрали (план, раздел 11, строка 34). */
    private final Map<UUID, UUID> homes = new LinkedHashMap<>();
    private Rights defaults;
    /** Сколько правок земли было: откат переезда сверяет это число. */
    private long edits;
    private Changes changes = Changes.NONE;

    /** Кто слушает правки чанков: рассылка клиентам ({@code server.ClaimSync}). Зовётся сразу после правки. */
    public interface Changes {
        Changes NONE = new Changes() {
            @Override
            public void chunk(String dimension, long chunk) {}

            @Override
            public void all() {}
        };

        /** Чанк занят, освобождён, передан, у него сменилась прогрузка или отсчёт. */
        void chunk(String dimension, long chunk);

        /** Поменялось всё сразу (переезд, откат). */
        void all();
    }

    public void listen(@Nullable Changes listener) {
        this.changes = listener == null ? Changes.NONE : listener;
    }

    public ClaimData(Rights defaults) {
        this.defaults = defaults.copy();
    }

    /** Права, которые получает новая команда. */
    public void defaults(Rights rights) {
        this.defaults = rights.copy();
    }

    // ---- чтение ----

    public @Nullable Claim get(String dimension, long chunk) {
        Long2ObjectOpenHashMap<Claim> map = dims.get(dimension);
        return map == null ? null : map.get(chunk);
    }

    public @Nullable UUID owner(String dimension, long chunk) {
        Claim c = get(dimension, chunk);
        return c == null ? null : c.team;
    }

    /** Есть ли в измерении хоть одна земля: дешёвый выход для проверок. */
    public boolean anyIn(String dimension) {
        Long2ObjectOpenHashMap<Claim> map = dims.get(dimension);
        return map != null && !map.isEmpty();
    }

    /** Чанки измерения, только для чтения. */
    public Long2ObjectMap<Claim> claims(String dimension) {
        Long2ObjectOpenHashMap<Claim> map = dims.get(dimension);
        return map == null ? Long2ObjectMaps.emptyMap() : Long2ObjectMaps.unmodifiable(map);
    }

    public List<String> dimensions() {
        return List.copyOf(dims.keySet());
    }

    /** Команда-дом подуровня Sable или null. */
    public @Nullable UUID home(UUID subLevel) {
        return homes.get(subLevel);
    }

    /** Запоминает дом подуровня; null забывает. */
    public void home(UUID subLevel, @Nullable UUID team) {
        if (team == null) homes.remove(subLevel);
        else homes.put(subLevel, team);
    }

    public Map<UUID, UUID> homes() {
        return Collections.unmodifiableMap(homes);
    }

    public @Nullable TeamLand land(UUID team) {
        return teams.get(team);
    }

    public Map<UUID, TeamLand> lands() {
        return Collections.unmodifiableMap(teams);
    }

    public @Nullable PlayerBonus bonus(UUID player) {
        return players.get(player);
    }

    public Map<UUID, PlayerBonus> bonuses() {
        return Collections.unmodifiableMap(players);
    }

    public int claimed(UUID team) {
        TeamLand l = teams.get(team);
        return l == null ? 0 : l.claimed;
    }

    public int forced(UUID team) {
        TeamLand l = teams.get(team);
        return l == null ? 0 : l.forced;
    }

    public boolean isEmpty() {
        for (Long2ObjectOpenHashMap<Claim> map : dims.values()) if (!map.isEmpty()) return false;
        return true;
    }

    public int total() {
        int n = 0;
        for (Long2ObjectOpenHashMap<Claim> map : dims.values()) n += map.size();
        return n;
    }

    public long edits() {
        return edits;
    }

    // ---- правка ----

    public TeamLand landOrCreate(UUID team) {
        return teams.computeIfAbsent(team, id -> new TeamLand(id, defaults));
    }

    public PlayerBonus bonusOrCreate(UUID player) {
        return players.computeIfAbsent(player, id -> new PlayerBonus(0, 0));
    }

    /** Занимает чанк, если он свободен. Чужое не перезаписывает: тогда null. */
    public @Nullable Claim put(String dimension, long chunk, UUID team, @Nullable UUID by, long at) {
        Long2ObjectOpenHashMap<Claim> map = dims.computeIfAbsent(dimension, d -> new Long2ObjectOpenHashMap<>());
        if (map.containsKey(chunk)) return null;
        Claim c = new Claim(dimension, chunk, team, by, at);
        map.put(chunk, c);
        landOrCreate(team).claimed++;
        changes.chunk(dimension, chunk);
        return c;
    }

    public @Nullable Claim remove(String dimension, long chunk) {
        Long2ObjectOpenHashMap<Claim> map = dims.get(dimension);
        if (map == null) return null;
        Claim c = map.remove(chunk);
        if (c == null) return null;
        TeamLand l = landOrCreate(c.team);
        l.claimed--;
        if (c.force) l.forced--;
        if (map.isEmpty()) dims.remove(dimension);
        changes.chunk(dimension, chunk);
        return c;
    }

    public void setForce(Claim c, boolean on) {
        if (c.force == on) return;
        c.force = on;
        landOrCreate(c.team).forced += on ? 1 : -1;
        changed(c);
    }

    public void setRelease(Claim c, long releaseAt) {
        boolean was = c.releasing();
        c.releaseAt = releaseAt;
        if (was != c.releasing()) changed(c);
    }

    private void changed(Claim c) {
        changes.chunk(c.dimension, c.pos);
    }

    /** Отдаёт всю землю команды {@code from} команде {@code to} вместе с добавкой команды. Возвращает чанки по измерениям. */
    public Map<String, LongList> transfer(UUID from, UUID to) {
        return transfer(from, to, null);
    }

    /**
     * То же; участки уходящей команды переходят под новыми номерами с теми же именами, {@code plotMap} (если не null)
     * получает пары «новый номер → старый» для отмены.
     */
    public Map<String, LongList> transfer(UUID from, UUID to, @Nullable Map<Integer, Integer> plotMap) {
        Map<String, LongList> moved = new LinkedHashMap<>();
        if (from.equals(to)) return moved;
        TeamLand src = teams.get(from);
        Map<Integer, Integer> remap = new HashMap<>();
        if (src != null && !src.plots.isEmpty()) {
            TeamLand dst = landOrCreate(to);
            for (Plot p : src.plots.values()) {
                int id = dst.nextPlot++;
                dst.plots.put(id, new Plot(id, p.name, p.dim));
                remap.put(p.id, id);
                if (plotMap != null) plotMap.put(id, p.id);
            }
            src.plots.clear();
        }
        for (Map.Entry<String, Long2ObjectOpenHashMap<Claim>> e : dims.entrySet()) {
            LongList list = new LongArrayList();
            for (Long2ObjectMap.Entry<Claim> c : e.getValue().long2ObjectEntrySet()) {
                if (c.getValue().team.equals(from)) list.add(c.getLongKey());
            }
            for (long chunk : list) {
                reassign(e.getKey(), chunk, to);
                Claim c = e.getValue().get(chunk);
                if (c != null) c.plot = remap.getOrDefault(c.plot, 0);
            }
            if (!list.isEmpty()) moved.put(e.getKey(), list);
        }
        if (src != null) {
            TeamLand dst = landOrCreate(to);
            dst.bonusClaims += src.bonusClaims;
            dst.bonusForce += src.bonusForce;
            src.bonusClaims = 0;
            src.bonusForce = 0;
        }
        return moved;
    }

    /** Возвращает участки после отмены слияния: чанки {@code team} с номером из {@code plotMap} получают старый номер. */
    public void restorePlots(UUID team, UUID from, Map<Integer, Integer> plotMap) {
        TeamLand dst = teams.get(from), src = landOrCreate(team);
        if (dst == null) return;
        for (Map.Entry<Integer, Integer> e : plotMap.entrySet()) {
            Plot p = dst.plots.remove(e.getKey());
            if (p != null) src.plots.put(e.getValue(), new Plot(e.getValue(), p.name, p.dim));
            src.nextPlot = Math.max(src.nextPlot, e.getValue() + 1);
        }
        for (Long2ObjectOpenHashMap<Claim> map : dims.values()) {
            for (Claim c : map.values()) {
                if (c.team.equals(team) && plotMap.containsKey(c.plot)) c.plot = plotMap.get(c.plot);
            }
        }
    }

    /** Переписывает чанк на другую команду, сохраняя время, прогрузку и отсчёт. */
    public void reassign(String dimension, long chunk, UUID team) {
        Long2ObjectOpenHashMap<Claim> map = dims.get(dimension);
        Claim old = map == null ? null : map.get(chunk);
        if (old == null || old.team.equals(team)) return;
        Claim c = new Claim(dimension, chunk, team, old.by, old.at);
        c.plot = old.plot;
        c.force = old.force;
        c.releaseAt = old.releaseAt;
        map.put(chunk, c);
        TeamLand src = landOrCreate(old.team), dst = landOrCreate(team);
        src.claimed--;
        dst.claimed++;
        if (old.force) {
            src.forced--;
            dst.forced++;
        }
        changes.chunk(dimension, chunk);
    }

    /** Заменяет всё содержимое посчитанной базой: переезд кладёт её одним шагом. Счётчик правок не меняется. */
    public void replaceWith(ClaimData other) {
        dims.clear();
        dims.putAll(other.dims);
        teams.clear();
        teams.putAll(other.teams);
        players.clear();
        players.putAll(other.players);
        archive = other.archive.copy();
        homes.putAll(other.homes);
        changes.all();
    }

    /** Пустая база: откат переезда. Счётчик правок не меняется. */
    public void clear() {
        dims.clear();
        teams.clear();
        players.clear();
        archive = new ListTag();
        changes.all();
    }

    /** Отмечает правку земли игроком или оператором (не переездом). */
    public void edited() {
        edits++;
    }

    // ---- NBT ----

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("v", VERSION);
        tag.putLong("edits", edits);
        Map<UUID, Integer> index = new HashMap<>();
        ListTag ids = new ListTag();
        CompoundTag claims = new CompoundTag();
        for (Map.Entry<String, Long2ObjectOpenHashMap<Claim>> e : dims.entrySet()) {
            ListTag list = new ListTag();
            for (Long2ObjectMap.Entry<Claim> x : e.getValue().long2ObjectEntrySet()) {
                Claim c = x.getValue();
                int team = index.computeIfAbsent(c.team, u -> {
                    ids.add(StringTag.valueOf(u.toString()));
                    return ids.size() - 1;
                });
                CompoundTag t = new CompoundTag();
                t.putLong("pos", x.getLongKey());
                t.putInt("team", team);
                if (c.by != null) t.putUUID("by", c.by);
                t.putLong("at", c.at);
                t.putInt("plot", c.plot);
                t.putBoolean("force", c.force);
                t.putLong("releaseAt", c.releaseAt);
                list.add(t);
            }
            if (!list.isEmpty()) claims.put(e.getKey(), list);
        }
        tag.put("ids", ids);
        CompoundTag t = new CompoundTag();
        for (TeamLand land : teams.values()) if (land.worthSaving(defaults)) t.put(land.id.toString(), land.save());
        tag.put("teams", t);
        tag.put("claims", claims);
        CompoundTag p = new CompoundTag();
        for (Map.Entry<UUID, PlayerBonus> e : players.entrySet()) {
            if (e.getValue().isEmpty()) continue;
            CompoundTag b = new CompoundTag();
            b.putInt("bonusClaims", e.getValue().claims);
            b.putInt("bonusForce", e.getValue().force);
            p.put(e.getKey().toString(), b);
        }
        tag.put("players", p);
        tag.put("archive", archive.copy());
        if (!homes.isEmpty()) {
            ListTag h = new ListTag();
            homes.forEach((sub, team) -> {
                CompoundTag one = new CompoundTag();
                one.putUUID("sub", sub);
                one.putUUID("team", team);
                h.add(one);
            });
            tag.put("homes", h);
        }
        return tag;
    }

    /** Читает файл. Версия новее известной это ошибка: старый мод не должен затереть новый формат. */
    public static ClaimData load(CompoundTag tag, Rights defaults) {
        int v = tag.getInt("v");
        if (v > VERSION) throw new IllegalStateException("rysclaims.dat версии " + v + " новее мода (" + VERSION + ")");
        ClaimData data = new ClaimData(defaults);
        data.edits = tag.getLong("edits");
        CompoundTag teams = tag.getCompound("teams");
        for (String key : teams.getAllKeys()) {
            UUID id = uuid(key);
            if (id != null) data.teams.put(id, TeamLand.load(id, teams.getCompound(key), defaults));
        }
        List<UUID> ids = new ArrayList<>();
        for (Tag x : tag.getList("ids", Tag.TAG_STRING)) ids.add(uuid(x.getAsString()));
        CompoundTag claims = tag.getCompound("claims");
        for (String dim : claims.getAllKeys()) {
            for (Tag x : claims.getList(dim, Tag.TAG_COMPOUND)) {
                CompoundTag t = (CompoundTag) x;
                int i = t.getInt("team");
                UUID team = i >= 0 && i < ids.size() ? ids.get(i) : null;
                if (team == null) continue;
                Claim c = data.put(dim, t.getLong("pos"), team, t.hasUUID("by") ? t.getUUID("by") : null, t.getLong("at"));
                if (c == null) continue;
                c.plot = t.getInt("plot");
                data.setForce(c, t.getBoolean("force"));
                c.releaseAt = t.getLong("releaseAt");
            }
        }
        CompoundTag players = tag.getCompound("players");
        for (String key : players.getAllKeys()) {
            UUID id = uuid(key);
            if (id == null) continue;
            CompoundTag b = players.getCompound(key);
            data.players.put(id, new PlayerBonus(b.getInt("bonusClaims"), b.getInt("bonusForce")));
        }
        data.archive = tag.getList("archive", Tag.TAG_COMPOUND).copy();
        for (Tag x : tag.getList("homes", Tag.TAG_COMPOUND)) {
            CompoundTag t = (CompoundTag) x;
            if (t.hasUUID("sub") && t.hasUUID("team")) data.homes.put(t.getUUID("sub"), t.getUUID("team"));
        }
        return data;
    }

    private static @Nullable UUID uuid(String s) {
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}

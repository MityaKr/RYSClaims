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
 *                  machineNames, legacy?, plots } }
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
    private Rights defaults;
    /** Сколько правок земли было: откат переезда сверяет это число. */
    private long edits;

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
        Claim c = new Claim(team, by, at);
        map.put(chunk, c);
        landOrCreate(team).claimed++;
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
        return c;
    }

    public void setForce(Claim c, boolean on) {
        if (c.force == on) return;
        c.force = on;
        landOrCreate(c.team).forced += on ? 1 : -1;
    }

    public void setRelease(Claim c, long releaseAt) {
        c.releaseAt = releaseAt;
    }

    /** Отдаёт всю землю команды {@code from} команде {@code to} вместе с добавкой команды. Возвращает чанки по измерениям. */
    public Map<String, LongList> transfer(UUID from, UUID to) {
        Map<String, LongList> moved = new LinkedHashMap<>();
        if (from.equals(to)) return moved;
        for (Map.Entry<String, Long2ObjectOpenHashMap<Claim>> e : dims.entrySet()) {
            LongList list = new LongArrayList();
            for (Long2ObjectMap.Entry<Claim> c : e.getValue().long2ObjectEntrySet()) {
                if (c.getValue().team.equals(from)) list.add(c.getLongKey());
            }
            for (long chunk : list) reassign(e.getKey(), chunk, to);
            if (!list.isEmpty()) moved.put(e.getKey(), list);
        }
        TeamLand src = teams.get(from);
        if (src != null) {
            TeamLand dst = landOrCreate(to);
            dst.bonusClaims += src.bonusClaims;
            dst.bonusForce += src.bonusForce;
            src.bonusClaims = 0;
            src.bonusForce = 0;
        }
        return moved;
    }

    /** Переписывает чанк на другую команду, сохраняя время, прогрузку и отсчёт. */
    public void reassign(String dimension, long chunk, UUID team) {
        Long2ObjectOpenHashMap<Claim> map = dims.get(dimension);
        Claim old = map == null ? null : map.get(chunk);
        if (old == null || old.team.equals(team)) return;
        Claim c = new Claim(team, old.by, old.at);
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

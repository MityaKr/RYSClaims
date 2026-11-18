package works.repka.rysclaims.server;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;
import works.repka.rysclaims.data.Claim;
import works.repka.rysclaims.data.ClaimData;
import works.repka.rysclaims.data.Settings;
import works.repka.rysclaims.data.TeamLand;

/**
 * Какие чанки должны быть прогружены и чем это отличается от поставленных билетов. Без игры: билеты ставит {@link Loader}.
 *
 * <p>Чанк прогружен, если у него включена прогрузка и команда сейчас «активна»: кто-то из неё в сети, или сервер разрешил
 * режим «всегда» и команда его выбрала.
 */
public final class LoadPlan {
    /** Изменение билета: поставить или снять. */
    public record Change(String dimension, long chunk, UUID team, boolean add) {}

    private LoadPlan() {}

    /** Нужна ли прогрузка команде прямо сейчас. */
    public static boolean active(Settings s, @org.jetbrains.annotations.Nullable TeamLand land, boolean online) {
        if (online) return true;
        return s.maxLoad == Settings.LoadMode.ALWAYS && land != null && land.loadMode == Settings.LoadMode.ALWAYS;
    }

    /** Нужные билеты: измерение → чанк → команда-владелец билета. */
    public static Map<String, Long2ObjectMap<UUID>> desired(ClaimData data, Settings s, Predicate<UUID> online) {
        Map<String, Long2ObjectMap<UUID>> out = new LinkedHashMap<>();
        Map<UUID, Boolean> active = new java.util.HashMap<>();
        for (String dim : data.dimensions()) {
            for (Long2ObjectMap.Entry<Claim> e : data.claims(dim).long2ObjectEntrySet()) {
                Claim c = e.getValue();
                if (!c.force()) continue;
                boolean on = active.computeIfAbsent(c.team(), t -> active(s, data.land(t), online.test(t)));
                if (on) out.computeIfAbsent(dim, d -> new Long2ObjectOpenHashMap<>()).put(e.getLongKey(), c.team());
            }
        }
        return out;
    }

    /** Что снять и что поставить, чтобы поставленное стало нужным. Сначала снятия. */
    public static List<Change> diff(Map<String, Long2ObjectMap<UUID>> applied, Map<String, Long2ObjectMap<UUID>> desired) {
        List<Change> remove = new ArrayList<>(), add = new ArrayList<>();
        for (Map.Entry<String, Long2ObjectMap<UUID>> e : applied.entrySet()) {
            Long2ObjectMap<UUID> want = desired.get(e.getKey());
            for (Long2ObjectMap.Entry<UUID> x : e.getValue().long2ObjectEntrySet()) {
                UUID w = want == null ? null : want.get(x.getLongKey());
                if (!x.getValue().equals(w)) remove.add(new Change(e.getKey(), x.getLongKey(), x.getValue(), false));
            }
        }
        for (Map.Entry<String, Long2ObjectMap<UUID>> e : desired.entrySet()) {
            Long2ObjectMap<UUID> have = applied.get(e.getKey());
            for (Long2ObjectMap.Entry<UUID> x : e.getValue().long2ObjectEntrySet()) {
                UUID h = have == null ? null : have.get(x.getLongKey());
                if (!x.getValue().equals(h)) add.add(new Change(e.getKey(), x.getLongKey(), x.getValue(), true));
            }
        }
        remove.addAll(add);
        return remove;
    }

    /** Применяет изменение к таблице поставленного. */
    public static void apply(Map<String, Long2ObjectMap<UUID>> applied, Change c) {
        if (c.add()) applied.computeIfAbsent(c.dimension(), d -> new Long2ObjectOpenHashMap<>()).put(c.chunk(), c.team());
        else {
            Long2ObjectMap<UUID> m = applied.get(c.dimension());
            if (m != null && c.team().equals(m.get(c.chunk()))) {
                m.remove(c.chunk());
                if (m.isEmpty()) applied.remove(c.dimension());
            }
        }
    }
}

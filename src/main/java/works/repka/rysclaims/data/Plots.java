package works.repka.rysclaims.data;

import it.unimi.dsi.fastutil.longs.Long2IntFunction;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongCollection;
import it.unimi.dsi.fastutil.longs.LongList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Участки (план, раздел 6): группы чанков одной команды в одном измерении, которые касаются стороной (углом не считается).
 * У участка постоянный номер и имя; пустое имя показывается как «Участок N».
 *
 * <p>Одно правило на все перемены ({@link #assign}): группы пересчитываются, от больших к меньшим; группа берёт номер,
 * которого у её чанков было больше всего и который ещё не взят группой крупнее. Так:
 * <ul>
 *   <li>новый чанк соединил два участка: остаётся имя большего, второе уходит записью в ленту ({@link Event.Kind#MERGE});</li>
 *   <li>освобождение разрезало участок: большая часть хранит имя, меньшая получает номер («База 2», {@link Event.Kind#SPLIT});</li>
 *   <li>чанк без участка (старый файл, переезд) получает номер соседей или новый участок без имени.</li>
 * </ul>
 * Тот же расчёт делает клиент на своей копии участков, чтобы до подтверждения сказать «Соединит участки: База и Шахта».
 */
public final class Plots {
    private Plots() {}

    /** Сколько знаков в имени участка. */
    public static final int MAX_NAME = 32;

    /** Что случилось с участками при пересчёте. */
    public record Event(Kind kind, int keptId, String kept, int otherId, String other) {
        public enum Kind {
            /** Участок {@code other} влился в {@code kept}, его имя ушло. */
            MERGE,
            /** От участка {@code kept} отрезан новый участок {@code other}. */
            SPLIT
        }
    }

    /** Итог пересчёта: номер каждого чанка, новые участки с именами, исчезнувшие номера, события. */
    public static final class Assignment {
        public final Long2IntOpenHashMap plot = new Long2IntOpenHashMap();
        public final Map<Integer, String> created = new LinkedHashMap<>();
        public final Set<Integer> removed = new HashSet<>();
        public final List<Event> events = new ArrayList<>();
        public int nextId;
    }

    /**
     * Пересчёт участков одной команды в одном измерении.
     *
     * @param chunks   все чанки команды в измерении после перемены
     * @param old      номер участка чанка до перемены (0 или неизвестный номер: без участка)
     * @param names    участки команды в этом измерении до перемены: номер и имя
     * @param allNames имена всех участков команды (для «База 2» без повтора)
     * @param nextId   первый свободный номер
     */
    public static Assignment assign(LongCollection chunks, Long2IntFunction old, Map<Integer, String> names, Collection<String> allNames, int nextId) {
        Assignment out = new Assignment();
        out.nextId = Math.max(1, nextId);
        Set<String> taken = new HashSet<>(allNames);
        Set<Integer> used = new HashSet<>();
        Map<Integer, Integer> chosenFor = new HashMap<>(); // старый номер → номер группы, куда ушли его чанки (больше всего)
        for (LongList group : groups(chunks)) {
            Map<Integer, Integer> counts = new LinkedHashMap<>();
            for (int i = 0; i < group.size(); i++) {
                int id = old.get(group.getLong(i));
                if (id > 0 && names.containsKey(id)) counts.merge(id, 1, Integer::sum);
            }
            List<Integer> candidates = new ArrayList<>(counts.keySet());
            candidates.sort((a, b) -> counts.get(b).equals(counts.get(a)) ? Integer.compare(a, b) : Integer.compare(counts.get(b), counts.get(a)));
            Integer chosen = null;
            for (int id : candidates) {
                if (!used.contains(id)) {
                    chosen = id;
                    break;
                }
            }
            int id;
            if (chosen != null) {
                id = chosen;
            } else {
                id = out.nextId++;
                String name = "";
                if (!candidates.isEmpty()) {
                    int origin = candidates.get(0);
                    name = splitName(names.get(origin), taken);
                    out.events.add(new Event(Event.Kind.SPLIT, origin, names.get(origin), id, name));
                }
                if (!name.isEmpty()) taken.add(name);
                out.created.put(id, name);
            }
            used.add(id);
            for (int other : candidates) if (other != id) chosenFor.putIfAbsent(other, id);
            for (int i = 0; i < group.size(); i++) out.plot.put(group.getLong(i), id);
        }
        for (Map.Entry<Integer, String> e : names.entrySet()) {
            int id = e.getKey();
            if (used.contains(id)) continue;
            out.removed.add(id);
            Integer into = chosenFor.get(id);
            if (into != null && names.containsKey(into)) out.events.add(new Event(Event.Kind.MERGE, into, names.get(into), id, e.getValue()));
        }
        return out;
    }

    /** Имя отрезанной части: «База» → «База 2» (следующий свободный номер); у участка без имени пусто. */
    static String splitName(String base, Set<String> taken) {
        if (base == null || base.isEmpty()) return "";
        String stem = base;
        int n = 2;
        // «База 2» режется дальше в «База 3», а не в «База 2 2»
        int sp = base.lastIndexOf(' ');
        if (sp > 0) {
            try {
                n = Integer.parseInt(base.substring(sp + 1)) + 1;
                stem = base.substring(0, sp);
            } catch (NumberFormatException ignored) {
                // имя не кончается числом
            }
        }
        while (true) {
            String name = stem + " " + n;
            if (!taken.contains(name) && name.length() <= MAX_NAME) return name;
            if (name.length() > MAX_NAME) return "";
            n++;
        }
    }

    /** Группы чанков, касающихся стороной, от больших к меньшим (при равенстве по наименьшему чанку: порядок не зависит от хеша). */
    public static List<LongList> groups(LongCollection chunks) {
        LongOpenHashSet left = new LongOpenHashSet(chunks);
        long[] order = left.toLongArray();
        java.util.Arrays.sort(order);
        List<LongList> out = new ArrayList<>();
        LongArrayFIFOQueue queue = new LongArrayFIFOQueue();
        for (long start : order) {
            if (!left.remove(start)) continue;
            LongList group = new LongArrayList();
            queue.enqueue(start);
            while (!queue.isEmpty()) {
                long c = queue.dequeueLong();
                group.add(c);
                for (long s : Chunks.sides(c)) if (left.remove(s)) queue.enqueue(s);
            }
            out.add(group);
        }
        out.sort((a, b) -> Integer.compare(b.size(), a.size()));
        return out;
    }

    /** Группы чанков команды в измерении, от больших к меньшим. */
    public static List<LongList> groups(ClaimData data, UUID team, String dimension) {
        return groups(chunksOf(data, team, dimension));
    }

    static LongOpenHashSet chunksOf(ClaimData data, UUID team, String dimension) {
        LongOpenHashSet set = new LongOpenHashSet();
        for (Long2ObjectMap.Entry<Claim> e : data.claims(dimension).long2ObjectEntrySet()) {
            if (e.getValue().team().equals(team)) set.add(e.getLongKey());
        }
        return set;
    }

    /**
     * Пересчитывает участки команды в измерении и пишет итог в данные: номера чанков, новые участки, убранные номера.
     * Возвращает события для ленты.
     */
    public static List<Event> rebuild(ClaimData data, UUID team, String dimension) {
        TeamLand land = data.landOrCreate(team);
        LongOpenHashSet chunks = chunksOf(data, team, dimension);
        Map<Integer, String> names = new LinkedHashMap<>();
        List<String> all = new ArrayList<>();
        for (Plot p : land.plots.values()) {
            if (p.dim.equals(dimension)) names.put(p.id, p.name);
            all.add(p.name);
        }
        if (chunks.isEmpty() && names.isEmpty()) return List.of();
        Assignment a = assign(chunks, c -> {
            Claim cl = data.get(dimension, c);
            return cl == null ? 0 : cl.plot;
        }, names, all, land.nextPlot);
        land.nextPlot = a.nextId;
        for (int id : a.removed) land.plots.remove(id);
        a.created.forEach((id, name) -> land.plots.put(id, new Plot(id, name, dimension)));
        // отрезанная часть участка со своими правами получает те же права
        for (Event ev : a.events) {
            if (ev.kind() != Event.Kind.SPLIT) continue;
            Plot origin = land.plots.get(ev.keptId());
            Plot part = land.plots.get(ev.otherId());
            if (origin != null && part != null && origin.rights != null) part.rights = origin.rights.copy();
        }
        for (Long2IntOpenHashMap.Entry e : a.plot.long2IntEntrySet()) {
            Claim cl = data.get(dimension, e.getLongKey());
            if (cl != null) cl.plot = e.getIntValue();
        }
        return a.events;
    }

    /** Чанк группы, ближайший к её середине: по нему группа показывается координатами. */
    public static long center(LongList group) {
        double sx = 0, sz = 0;
        for (int i = 0; i < group.size(); i++) {
            sx += Chunks.x(group.getLong(i));
            sz += Chunks.z(group.getLong(i));
        }
        sx /= group.size();
        sz /= group.size();
        long best = group.getLong(0);
        double bd = Double.MAX_VALUE;
        for (int i = 0; i < group.size(); i++) {
            long c = group.getLong(i);
            double d = Math.pow(Chunks.x(c) - sx, 2) + Math.pow(Chunks.z(c) - sz, 2);
            if (d < bd) {
                bd = d;
                best = c;
            }
        }
        return best;
    }
}

package works.repka.rysclaims.data;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Участки на лету: группы чанков одной команды, которые касаются стороной (углом не считается). В 0.1 у участков нет имён
 * и они не хранятся: {@code /claims list} считает их при вызове.
 */
public final class Plots {
    private Plots() {}

    /** Группы чанков команды в измерении, от больших к меньшим. */
    public static List<LongList> groups(ClaimData data, UUID team, String dimension) {
        LongOpenHashSet left = new LongOpenHashSet();
        for (Long2ObjectMap.Entry<Claim> e : data.claims(dimension).long2ObjectEntrySet()) {
            if (e.getValue().team().equals(team)) left.add(e.getLongKey());
        }
        List<LongList> out = new ArrayList<>();
        LongArrayFIFOQueue queue = new LongArrayFIFOQueue();
        while (!left.isEmpty()) {
            long start = left.iterator().nextLong();
            left.remove(start);
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

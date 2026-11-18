package works.repka.rysclaims.data;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongList;

/** Упакованные координаты чанка: тот же формат, что {@code ChunkPos.asLong}, чтобы данные шли в игру без пересчёта. */
public final class Chunks {
    private Chunks() {}

    public static long pack(int x, int z) {
        return (x & 0xFFFFFFFFL) | ((z & 0xFFFFFFFFL) << 32);
    }

    public static int x(long chunk) {
        return (int) chunk;
    }

    public static int z(long chunk) {
        return (int) (chunk >>> 32);
    }

    /** Квадрат со стороной {@code 2r+1} вокруг чанка, от центра по кольцам: при нехватке лимита занимается ближнее. */
    public static LongList square(int cx, int cz, int radius) {
        LongList out = new LongArrayList((2 * radius + 1) * (2 * radius + 1));
        out.add(pack(cx, cz));
        for (int r = 1; r <= radius; r++) {
            for (int dx = -r; dx <= r; dx++) {
                out.add(pack(cx + dx, cz - r));
                out.add(pack(cx + dx, cz + r));
            }
            for (int dz = -r + 1; dz <= r - 1; dz++) {
                out.add(pack(cx - r, cz + dz));
                out.add(pack(cx + r, cz + dz));
            }
        }
        return out;
    }

    /** Четыре соседа по стороне: касание углом соседством не считается. */
    public static long[] sides(long chunk) {
        int x = x(chunk), z = z(chunk);
        return new long[] {pack(x + 1, z), pack(x - 1, z), pack(x, z + 1), pack(x, z - 1)};
    }

    public static String text(long chunk) {
        return x(chunk) + ", " + z(chunk);
    }
}

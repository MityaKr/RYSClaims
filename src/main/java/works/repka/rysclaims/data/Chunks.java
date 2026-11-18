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

    /**
     * Чанк лежит в сетке участков Sable (R0, раздел 3): контейнер {@code (7, 7, 10000, 10000)}, участок 2^7 чанков, сетка
     * 128 × 128 участков с начала 10000, то есть чанки от 1 280 000 до 1 296 383 по обеим осям. Занять такой чанк нельзя:
     * там лежат блоки летающих построек, их защиту решает земля под постройкой.
     */
    public static boolean sablePlot(int cx, int cz) {
        return inPlotRange(cx) && inPlotRange(cz);
    }

    public static boolean sablePlot(long chunk) {
        return sablePlot(x(chunk), z(chunk));
    }

    private static boolean inPlotRange(int c) {
        int plot = (c >> 7) - 10000;
        return plot >= 0 && plot < 128;
    }

    public static String text(long chunk) {
        return x(chunk) + ", " + z(chunk);
    }
}

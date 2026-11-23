package works.repka.rysclaims.net;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.LongFunction;
import org.jetbrains.annotations.Nullable;
import works.repka.rysclaims.data.Chunks;
import works.repka.rysclaims.data.Claim;

/**
 * Регион земли для сети: 32×32 чанка, на чанк один байт. Младшие шесть бит это номер команды в палитре региона плюс один
 * (0 значит ничья земля, до 63 команд на регион), бит {@link #FORCE} прогрузка, бит {@link #RELEASING} «освобождается».
 *
 * <p>Байты уходят сжатыми: пустой регион одним байтом, иначе повторы (байт и длина), а если повторы длиннее, как есть.
 * Поэтому регион никогда не больше 1025 байт плюс палитра, а обычный занимает десятки байт.
 */
public final class RegionBytes {
    public static final int SIDE = 32, SIZE = SIDE * SIDE;
    public static final int MAX_PALETTE = 63;
    public static final int TEAM_MASK = 0x3F, FORCE = 0x40, RELEASING = 0x80;
    private static final int EMPTY = 0, RAW = 1, RUNS = 2;

    private RegionBytes() {}

    /** Номер чанка внутри региона. */
    public static int index(int chunkX, int chunkZ) {
        return (chunkZ & (SIDE - 1)) * SIDE + (chunkX & (SIDE - 1));
    }

    /** Регион чанка упакован как чанк ({@link Chunks#pack}). */
    public static long regionOf(long chunk) {
        return Chunks.pack(Chunks.x(chunk) >> 5, Chunks.z(chunk) >> 5);
    }

    /** Байт чанка по записи; {@code team} номер в палитре с нуля. */
    public static byte code(int team, boolean force, boolean releasing) {
        int c = (Math.min(team, MAX_PALETTE - 1) + 1) & TEAM_MASK;
        if (force) c |= FORCE;
        if (releasing) c |= RELEASING;
        return (byte) c;
    }

    /** Регион, собранный на сервере: палитра команд и байты чанков. */
    public record Built(List<UUID> palette, byte[] codes) {
        public boolean empty() {
            return palette.isEmpty();
        }
    }

    /**
     * Собирает регион {@code rx, rz} по чанкам. Команд больше {@link #MAX_PALETTE} на одном регионе не бывает на деле;
     * если всё же так, лишние показываются последней командой палитры.
     */
    public static Built build(LongFunction<@Nullable Claim> claims, int rx, int rz) {
        List<UUID> palette = new ArrayList<>();
        byte[] codes = new byte[SIZE];
        for (int z = 0; z < SIDE; z++) {
            for (int x = 0; x < SIDE; x++) {
                Claim c = claims.apply(Chunks.pack(rx * SIDE + x, rz * SIDE + z));
                if (c == null) continue;
                int i = palette.indexOf(c.team());
                if (i < 0) {
                    if (palette.size() < MAX_PALETTE) {
                        palette.add(c.team());
                        i = palette.size() - 1;
                    } else i = MAX_PALETTE - 1;
                }
                codes[z * SIDE + x] = code(i, c.force(), c.releasing());
            }
        }
        return new Built(palette, codes);
    }

    public static byte[] encode(byte[] codes) {
        if (codes.length != SIZE) throw new IllegalArgumentException("a region is " + SIZE + " bytes");
        boolean empty = true;
        for (byte b : codes) {
            if (b != 0) {
                empty = false;
                break;
            }
        }
        if (empty) return new byte[] {EMPTY};
        ByteArrayOutputStream runs = new ByteArrayOutputStream();
        runs.write(RUNS);
        for (int i = 0; i < SIZE; ) {
            int j = i + 1;
            while (j < SIZE && codes[j] == codes[i]) j++;
            runs.write(codes[i]);
            varint(runs, j - i);
            i = j;
            if (runs.size() > SIZE) break;
        }
        if (runs.size() <= SIZE) return runs.toByteArray();
        byte[] raw = new byte[SIZE + 1];
        raw[0] = RAW;
        System.arraycopy(codes, 0, raw, 1, SIZE);
        return raw;
    }

    public static byte[] decode(byte[] data) {
        if (data.length == 0) throw new IllegalArgumentException("empty region");
        byte[] codes = new byte[SIZE];
        switch (data[0]) {
            case EMPTY -> {
                return codes;
            }
            case RAW -> {
                if (data.length != SIZE + 1) throw new IllegalArgumentException("region of the wrong length");
                System.arraycopy(data, 1, codes, 0, SIZE);
                return codes;
            }
            case RUNS -> {
                int[] at = {1};
                int filled = 0;
                while (at[0] < data.length) {
                    byte b = data[at[0]++];
                    int run = readVarint(data, at);
                    if (run <= 0 || filled + run > SIZE) throw new IllegalArgumentException("run past the region edge");
                    for (int k = 0; k < run; k++) codes[filled++] = b;
                }
                if (filled != SIZE) throw new IllegalArgumentException("region not filled");
                return codes;
            }
            default -> throw new IllegalArgumentException("unknown region kind " + data[0]);
        }
    }

    private static void varint(ByteArrayOutputStream out, int v) {
        while ((v & ~0x7F) != 0) {
            out.write((v & 0x7F) | 0x80);
            v >>>= 7;
        }
        out.write(v);
    }

    private static int readVarint(byte[] data, int[] at) {
        int v = 0, shift = 0;
        while (true) {
            if (at[0] >= data.length || shift > 28) throw new IllegalArgumentException("broken number");
            int b = data[at[0]++] & 0xFF;
            v |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) return v;
            shift += 7;
        }
    }
}

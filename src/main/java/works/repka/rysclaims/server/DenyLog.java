package works.repka.rysclaims.server;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.function.LongSupplier;
import java.util.stream.Stream;
import org.jetbrains.annotations.Nullable;
import works.repka.rysclaims.RYSClaims;

/**
 * Журнал отказов (план, раздел 9.2): {@code world/rysclaims/log/<команда>.jsonl}, строка на запись, дозапись в конец. Машина,
 * которой отказано, повторяет попытку каждый тик, поэтому повторы склеиваются в памяти (кто, что, где с точностью до чанка)
 * и уходят в файл раз в минуту в рабочем потоке. Файл земли журнал не трогает. Без игры: путь, часы и исполнитель снаружи.
 */
public final class DenyLog {
    /** Сколько разных записей держим в памяти до записи: дальше новые теряются, повторы старых ещё считаются. */
    static final int MAX_PENDING = 4096;
    /** Сколько последних байт файла читает {@code /claims log}. */
    static final int TAIL_BYTES = 64 * 1024;

    /** Одна склеенная запись: кто (игрок или машина), почему, где, сколько раз и когда. */
    public record Entry(UUID team, @Nullable UUID actor, String name, boolean machine, String reason, String dim, int x, int y, int z,
                        long first, long last, int count) {
        String json() {
            JsonObject o = new JsonObject();
            o.addProperty("t", last);
            o.addProperty("f", first);
            o.addProperty("n", count);
            if (actor != null) o.addProperty("a", actor.toString());
            o.addProperty("name", name);
            if (machine) o.addProperty("m", true);
            o.addProperty("r", reason);
            o.addProperty("d", dim);
            o.addProperty("x", x);
            o.addProperty("y", y);
            o.addProperty("z", z);
            return o.toString();
        }

        static @Nullable Entry parse(UUID team, String line) {
            try {
                JsonObject o = JsonParser.parseString(line).getAsJsonObject();
                UUID actor = o.has("a") ? UUID.fromString(o.get("a").getAsString()) : null;
                return new Entry(team, actor, o.get("name").getAsString(), o.has("m") && o.get("m").getAsBoolean(),
                    o.get("r").getAsString(), o.get("d").getAsString(), o.get("x").getAsInt(), o.get("y").getAsInt(),
                    o.get("z").getAsInt(), o.get("f").getAsLong(), o.get("t").getAsLong(), o.get("n").getAsInt());
            } catch (RuntimeException e) {
                return null; // битая строка не мешает остальным
            }
        }
    }

    private record Key(UUID team, @Nullable UUID actor, String name, String reason, String dim, int cx, int cz) {}

    private static final class Pending {
        final boolean machine;
        final int x, y, z;
        final long first;
        long last;
        int count;

        Pending(boolean machine, int x, int y, int z, long now) {
            this.machine = machine;
            this.x = x;
            this.y = y;
            this.z = z;
            this.first = now;
            this.last = now;
        }
    }

    private final Path dir;
    private final LongSupplier clock;
    private final Executor writer;
    private final Map<Key, Pending> pending = new LinkedHashMap<>();
    private int lost;

    public DenyLog(Path dir, LongSupplier clock, Executor writer) {
        this.dir = dir;
        this.clock = clock;
        this.writer = writer;
    }

    /** Отказ: в память, склеивая с такой же записью того же чанка. Зовётся из главного потока. */
    public void add(UUID team, @Nullable UUID actor, String name, boolean machine, ClaimService.Reason reason, String dim, int x, int y, int z) {
        Key key = new Key(team, actor, name, reason.key(), dim, x >> 4, z >> 4);
        Pending p = pending.get(key);
        long now = clock.getAsLong();
        if (p == null) {
            if (pending.size() >= MAX_PENDING) {
                lost++;
                return;
            }
            p = new Pending(machine, x, y, z, now);
            pending.put(key, p);
        }
        p.last = now;
        p.count++;
    }

    /** Записи в памяти, ещё не ушедшие в файл. */
    public List<Entry> pending(UUID team) {
        List<Entry> out = new ArrayList<>();
        pending.forEach((k, p) -> {
            if (k.team.equals(team)) out.add(entry(k, p));
        });
        return out;
    }

    private static Entry entry(Key k, Pending p) {
        return new Entry(k.team, k.actor, k.name, p.machine, k.reason, k.dim, p.x, p.y, p.z, p.first, p.last, p.count);
    }

    /** Отдаёт накопленное рабочему потоку: дозапись в файлы команд. Зовётся раз в минуту и при остановке сервера. */
    public void flush() {
        if (pending.isEmpty()) return;
        Map<UUID, List<String>> byTeam = new LinkedHashMap<>();
        pending.forEach((k, p) -> byTeam.computeIfAbsent(k.team, t -> new ArrayList<>()).add(entry(k, p).json()));
        pending.clear();
        if (lost > 0) RYSClaims.LOG.warn("RYSClaims: журнал отказов переполнен, не записано {}", lost);
        lost = 0;
        writer.execute(() -> byTeam.forEach(this::append));
    }

    private void append(UUID team, List<String> lines) {
        try {
            Files.createDirectories(dir);
            Files.write(file(team), lines, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            RYSClaims.LOG.warn("RYSClaims: журнал отказов {} не записан: {}", team, e.toString());
        }
    }

    Path file(UUID team) {
        return dir.resolve(team + ".jsonl");
    }

    /** Последние записи команды, новые первыми: хвост файла и то, что ещё в памяти. */
    public List<Entry> recent(UUID team, int max) {
        List<Entry> out = new ArrayList<>(readTail(team));
        out.addAll(pending(team));
        out.sort(Comparator.comparingLong(Entry::last).reversed());
        return out.size() > max ? new ArrayList<>(out.subList(0, max)) : out;
    }

    private List<Entry> readTail(UUID team) {
        Path f = file(team);
        if (!Files.isRegularFile(f)) return List.of();
        try (RandomAccessFile raf = new RandomAccessFile(f.toFile(), "r")) {
            long len = raf.length();
            long from = Math.max(0, len - TAIL_BYTES);
            byte[] buf = new byte[(int) (len - from)];
            raf.seek(from);
            raf.readFully(buf);
            String text = new String(buf, StandardCharsets.UTF_8);
            String[] lines = text.split("\n");
            List<Entry> out = new ArrayList<>();
            for (int i = from > 0 ? 1 : 0; i < lines.length; i++) { // первая строка хвоста может быть обрезана
                if (lines[i].isBlank()) continue;
                Entry e = Entry.parse(team, lines[i].trim());
                if (e != null) out.add(e);
            }
            return out;
        } catch (IOException e) {
            return List.of();
        }
    }

    /** Убирает строки старше {@code days} дней, пустые файлы переносит в {@code _old}. В рабочем потоке, раз в сутки. */
    public void prune(int days) {
        long border = clock.getAsLong() - days * 86_400_000L;
        writer.execute(() -> {
            if (!Files.isDirectory(dir)) return;
            try (Stream<Path> files = Files.list(dir)) {
                for (Path f : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".jsonl"))::iterator) pruneFile(f, border);
            } catch (IOException e) {
                RYSClaims.LOG.warn("RYSClaims: чистка журнала отказов: {}", e.toString());
            }
        });
    }

    private void pruneFile(Path f, long border) throws IOException {
        String name = f.getFileName().toString();
        UUID team;
        try {
            team = UUID.fromString(name.substring(0, name.length() - ".jsonl".length()));
        } catch (IllegalArgumentException e) {
            return;
        }
        List<String> keep = new ArrayList<>();
        boolean changed = false;
        for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
            Entry e = Entry.parse(team, line);
            if (e != null && e.last() >= border) keep.add(line);
            else changed = true;
        }
        if (changed) Files.write(f, keep, StandardCharsets.UTF_8, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
    }
}

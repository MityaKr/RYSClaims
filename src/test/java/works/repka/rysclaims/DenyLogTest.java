package works.repka.rysclaims;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import works.repka.rysclaims.server.ClaimService.Reason;
import works.repka.rysclaims.server.DenyLog;

/** Журнал отказов без игры: склейка повторов в памяти, дозапись в файл команды, чтение хвоста, чистка старого. */
class DenyLogTest {
    static final UUID TEAM = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
    static final UUID OTHER = UUID.fromString("00000000-0000-0000-0000-0000000000bb");
    static final UUID MACHINE = UUID.fromString("41c82c87-7afb-4024-ba57-13d2c99cae77");
    static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-000000000004");

    long now = 1_000_000_000L;

    @Test
    void repeatsAreGluedAndWrittenOnce(@TempDir Path dir) throws IOException {
        DenyLog log = new DenyLog(dir, () -> now, Runnable::run);
        for (int i = 0; i < 1000; i++) {
            now += 50; // машина бьёт каждый тик
            log.add(TEAM, MACHINE, "[Minecraft]", true, Reason.MACHINE, "minecraft:overworld", 10 + (i % 3), 64, 10);
        }
        now += 1000;
        log.add(TEAM, PLAYER, "Grib_ok", false, Reason.BUILD, "minecraft:overworld", 100, 64, 100);
        log.add(OTHER, PLAYER, "Grib_ok", false, Reason.OPEN, "minecraft:the_nether", 0, 64, 0);
        assertEquals(2, log.pending(TEAM).size());
        log.flush();
        assertTrue(log.pending(TEAM).isEmpty());
        List<String> lines = Files.readAllLines(dir.resolve(TEAM + ".jsonl"), StandardCharsets.UTF_8);
        assertEquals(2, lines.size(), "тысяча ударов машины одной строкой");
        List<DenyLog.Entry> recent = log.recent(TEAM, 10);
        assertEquals("Grib_ok", recent.get(0).name(), "новые первыми");
        DenyLog.Entry m = recent.get(1);
        assertEquals(1000, m.count());
        assertTrue(m.machine());
        assertEquals(MACHINE, m.actor());
        assertEquals("machine", m.reason());
        assertTrue(Files.isRegularFile(dir.resolve(OTHER + ".jsonl")), "своя запись у каждой команды");
    }

    @Test
    void pendingIsSeenBeforeFlushAndOldLinesArePruned(@TempDir Path dir) throws IOException {
        DenyLog log = new DenyLog(dir, () -> now, Runnable::run);
        log.add(TEAM, PLAYER, "Grib_ok", false, Reason.BUILD, "minecraft:overworld", 0, 64, 0);
        log.flush();
        now += 8L * 86_400_000L;
        log.add(TEAM, PLAYER, "Grib_ok", false, Reason.USE, "minecraft:overworld", 0, 64, 0);
        assertEquals(2, log.recent(TEAM, 10).size(), "файл и память вместе");
        log.flush();
        log.prune(7);
        List<DenyLog.Entry> left = log.recent(TEAM, 10);
        assertEquals(1, left.size(), "запись старше 7 дней убрана");
        assertEquals("use", left.get(0).reason());
        Files.writeString(dir.resolve(TEAM + ".jsonl"), "битая строка\n", StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.APPEND);
        assertEquals(1, log.recent(TEAM, 10).size(), "битая строка не мешает");
        assertFalse(log.recent(OTHER, 10).iterator().hasNext());
    }
}

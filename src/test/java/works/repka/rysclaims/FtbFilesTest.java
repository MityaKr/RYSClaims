package works.repka.rysclaims;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import works.repka.rysclaims.data.Limits;
import works.repka.rysclaims.data.PlayerBonus;
import works.repka.rysclaims.data.Rights;
import works.repka.rysclaims.data.Settings;
import works.repka.rysclaims.migrate.FtbSettings;
import works.repka.rysclaims.migrate.FtbWorld;

/**
 * Чтение файлов FTB через {@code migrate.Snbt} ядра: заготовки в git и настоящие файлы сервера RepYS (вне git, тест
 * пропускается без папки). Ожидаемое считается из входных файлов, а не записано руками: лимит, посчитанный нашей
 * моделью из конфига и личных файлов, не меньше действующего лимита из файла команды FTB.
 */
class FtbFilesTest {
    static Path samples() {
        return Path.of(System.getProperty("rysclaims.samples", "src/test/resources/ftb"));
    }

    static FtbWorld read(Path root) throws IOException {
        return FtbWorld.read(root.resolve("world"),
            List.of(root.resolve("world/serverconfig/ftbchunks-world.snbt"), root.resolve("config/ftbchunks-world.snbt"), root.resolve("ftbchunks-world.snbt")));
    }

    /** Наша модель лимитов на данных FTB: общий из конфига, личные добавки из личных файлов. */
    static Limits limits(FtbWorld w, Settings s, FtbWorld.Team team) {
        Map<UUID, PlayerBonus> bonus = new HashMap<>();
        for (FtbWorld.Land l : w.lands.values()) {
            if (l.extraClaims != 0 || l.extraForce != 0) bonus.put(l.id, new PlayerBonus(l.extraClaims, l.extraForce));
        }
        return Limits.of(s, null, team.members(), team.owner == null ? team.id : team.owner, bonus::get);
    }

    @Test
    void samplesInGit() throws IOException {
        FtbWorld w = read(samples());
        assertTrue(w.errors.isEmpty(), w.errors.toString());
        assertEquals("00000000-1111-2222-3333-444444444444", w.serverId);
        FtbWorld.Team barsuki = w.teams.get(UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001"));
        assertEquals("Barsuki", barsuki.name);
        assertEquals("party", barsuki.type);
        assertEquals(2, barsuki.members().size()); // owner и member; ally и invited не участники
        assertEquals(1, barsuki.allies().size());
        assertEquals("private", barsuki.property("ftbchunks:block_edit_mode", ""));

        Settings s = FtbSettings.apply(w.config, new Settings());
        assertEquals(Settings.BonusMode.LARGEST, s.bonusMode);
        assertTrue(s.disabledDimensions.contains("test:void"));
        assertTrue(s.disabledDimensions.contains("ae2:spatial_storage")); // наши по умолчанию остаются
        assertEquals(Settings.LoadMode.ONLINE, s.maxLoad);
        // права новых команд из team_prop_defaults: «животные» по более строгому из interact (allies) и attack (private)
        assertTrue(s.defaultRights.allows(Rights.Action.BUILD, Rights.Relation.ALLY, null));
        assertTrue(s.defaultRights.allows(Rights.Action.OPEN, Rights.Relation.ALLY, null));
        assertTrue(!s.defaultRights.allows(Rights.Action.ENTITY, Rights.Relation.ALLY, null));

        FtbWorld.Land land = w.lands.get(barsuki.id);
        assertEquals(6, land.chunkCount());
        assertEquals(3, land.forcedCount());
        Limits l = limits(w, s, barsuki);
        assertEquals(land.maxClaims, l.claims); // 500 + наибольшая 10
        assertEquals(land.maxForce, l.force); // 25 + наибольшая 40
    }

    @Test
    void realRepysFiles() throws IOException {
        Path root = Path.of(System.getProperty("rysclaims.fixtures", "../../_shared/_saved/fixtures/ftb-2026-11-18"));
        assumeTrue(Files.isDirectory(root.resolve("world/ftbchunks")), "нет копии файлов FTB RepYS: " + root);
        FtbWorld w = read(root);
        assertTrue(w.errors.isEmpty(), w.errors.toString());
        assertNotNull(w.configFile);
        Settings s = FtbSettings.apply(w.config, new Settings());
        int parties = 0;
        for (FtbWorld.Team t : w.teams.values()) {
            FtbWorld.Land land = w.lands.get(t.id);
            if (land == null || land.chunkCount() == 0) continue;
            if ("party".equals(t.type)) parties++;
            Limits l = limits(w, s, t);
            // перенос не оставит команду сверх лимита: посчитанный лимит не меньше занятого и прогруженного
            assertTrue(l.claims >= land.chunkCount(), t.name + ": чанков " + land.chunkCount() + ", лимит " + l.claims);
            assertTrue(l.force >= land.forcedCount(), t.name + ": прогружено " + land.forcedCount() + ", лимит " + l.force);
            // и совпадает с действующим лимитом FTB (иначе разницу закроет добавка команде, этап З3)
            assertEquals(land.maxClaims, l.claims, t.name + " клеймы");
            assertEquals(land.maxForce, l.force, t.name + " прогрузка");
        }
        assertTrue(parties > 0, "в копии нет ни одной команды с землёй");
    }
}

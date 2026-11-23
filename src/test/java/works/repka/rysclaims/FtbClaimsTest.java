package works.repka.rysclaims;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import works.repka.rysclaims.data.Chunks;
import works.repka.rysclaims.data.ClaimData;
import works.repka.rysclaims.data.Limits;
import works.repka.rysclaims.data.Rights;
import works.repka.rysclaims.data.Settings;
import works.repka.rysclaims.data.TeamLand;
import works.repka.rysclaims.migrate.FtbClaims;
import works.repka.rysclaims.migrate.FtbWorld;

/**
 * Перенос земли FTB Chunks (этап З3): сверка сумм, личные добавки, права и переключатели, команды без пары, команда
 * типа server, битый файл, союзники в доверенные, повторяемость. Ожидаемое считается из входных файлов.
 */
class FtbClaimsTest {
    static final UUID BARSUKI = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
    static final UUID A = UUID.fromString("bbbbbbbb-0000-0000-0000-00000000000a");
    static final UUID B = UUID.fromString("bbbbbbbb-0000-0000-0000-00000000000b");
    static final UUID C = UUID.fromString("bbbbbbbb-0000-0000-0000-00000000000c");
    static final UUID D = UUID.fromString("bbbbbbbb-0000-0000-0000-00000000000d");
    static final UUID SERVER = UUID.fromString("cccccccc-0000-0000-0000-000000000001");

    static FtbWorld samples() throws IOException {
        return FtbFilesTest.read(FtbFilesTest.samples());
    }

    static void assertSums(FtbWorld w, FtbClaims.Plan p, FakeTeams teams) {
        int chunks = 0, forced = 0;
        for (FtbWorld.Land l : w.lands.values()) {
            chunks += l.chunkCount();
            forced += l.forcedCount();
        }
        assertEquals(chunks, p.data.total(), "сумма чанков");
        int ours = 0;
        for (TeamLand l : p.data.lands().values()) ours += l.forced();
        assertEquals(forced, ours, "сумма прогрузки");
        assertTrue(p.mismatches.isEmpty(), p.mismatches.toString());
        // доступ: у каждого, кто был участником или союзником в FTB, есть доступ «открывать» на земле своей бывшей команды
        for (FtbWorld.Land land : w.lands.values()) {
            FtbWorld.Team t = w.teams.get(land.id);
            if (t == null || land.chunkCount() == 0) continue;
            UUID to = p.target.get(land.id);
            TeamLand dst = p.data.land(to);
            for (UUID player : t.members()) {
                assertTrue(teams.teamOf(player).equals(to) || player.equals(to) || dst.trusted.contains(player) || dst.legacyMembers.contains(player),
                    player + " потерял доступ к земле " + t.name);
            }
            for (UUID ally : t.allies()) assertTrue(dst.trusted.contains(ally), "союзник " + ally + " не стал доверенным");
        }
        // перенос не оставляет команду сверх лимита
        for (TeamLand land : p.data.lands().values()) {
            Limits lim = Limits.of(p.settings, land, teams.members(land.id), teams.leader(land.id), p.data::bonus);
            assertTrue(lim.claims >= land.claimed(), land.id + " сверх лимита чанков");
            assertTrue(lim.force >= land.forced(), land.id + " сверх лимита прогрузки");
        }
    }

    @Test
    void teamWithoutPairStaysUnderOldUuid() throws IOException {
        FtbWorld w = samples();
        FakeTeams teams = new FakeTeams(); // RYSTeams пуст: все соло
        FtbClaims.Plan p = FtbClaims.plan(w, new Settings(), true, teams, List.of());
        assertSums(w, p, teams);
        assertEquals(BARSUKI, p.target.get(BARSUKI));
        TeamLand land = p.data.land(BARSUKI);
        assertEquals("Barsuki", land.legacyName);
        assertEquals(Set.of(A, B), land.legacyMembers);
        assertEquals(A, land.legacyOwner);
        assertEquals(6, land.claimed());
        assertEquals(3, land.forced());
        // права: строить только команда (private), открывать и двери с союзом, животные по более строгому (allies)
        assertFalse(land.rights.allows(Rights.Action.BUILD, Rights.Relation.ALLY, null));
        assertTrue(land.rights.allows(Rights.Action.OPEN, Rights.Relation.ALLY, null));
        assertTrue(land.rights.allows(Rights.Action.ENTITY, Rights.Relation.ALLY, null));
        assertFalse(land.rights.allows(Rights.Action.ENTITY, Rights.Relation.OTHER, null));
        // переключатели
        assertTrue(land.flag(TeamLand.Flag.MACHINES_PROFILE));
        assertFalse(land.flag(TeamLand.Flag.MACHINES_OUTSIDE));
        assertTrue(land.flag(TeamLand.Flag.MACHINES_INSIDE));
        assertFalse(land.flag(TeamLand.Flag.OWN_EXPLOSIONS));
        assertTrue(land.flag(TeamLand.Flag.PVP));
        assertEquals(List.of("[IF]"), land.machineNames);
        assertEquals(TeamLand.Visibility.ALLIES, land.visibility);
        // союзник-игрок стал доверенным
        assertTrue(land.trusted.contains(C));
        // личные добавки у владельцев
        assertEquals(40, p.data.bonus(A).force);
        assertEquals(10, p.data.bonus(B).claims);
        // у команды без пары нет участников в RYSTeams: личные добавки не работают, лимит FTB держит добавка команде
        Limits lim = Limits.of(p.settings, land, teams.members(BARSUKI), teams.leader(BARSUKI), p.data::bonus);
        assertTrue(lim.claims >= 510 && lim.force >= 65, lim.toString());
        // участники команды без пары остались своими: строят по столбцу команды, но управлять землёй не могут
        var service = new works.repka.rysclaims.server.ClaimService(p.data, () -> p.settings, teams, () -> 0L,
            new works.repka.rysclaims.server.ClaimService.Hooks() {});
        long c = Chunks.pack(10, 20);
        assertTrue(service.can(B, "minecraft:overworld", c, Rights.Action.BUILD));
        assertTrue(service.can(C, "minecraft:overworld", c, Rights.Action.OPEN));
        assertFalse(service.can(C, "minecraft:overworld", c, Rights.Action.BUILD));
        assertFalse(service.can(D, "minecraft:overworld", c, Rights.Action.OPEN));
        assertEquals(1, service.release(A, BARSUKI, "minecraft:overworld", it.unimi.dsi.fastutil.longs.LongArrayList.of(c), false)
            .skipped.get(works.repka.rysclaims.server.ClaimService.Skip.NO_RIGHT));
        // личная земля игрока D
        assertEquals(2, p.data.claimed(D));
        assertNull(p.data.land(D).legacyName);
    }

    @Test
    void teamKnownToRysTeamsKeepsUuid() throws IOException {
        FtbWorld w = samples();
        FakeTeams teams = new FakeTeams().team(BARSUKI, A, B);
        FtbClaims.Plan p = FtbClaims.plan(w, new Settings(), true, teams, List.of(BARSUKI));
        assertSums(w, p, teams);
        TeamLand land = p.data.land(BARSUKI);
        assertNull(land.legacyName);
        // лимит считает наша модель: 500 + наибольшая 10, 25 + наибольшая 40 = как в файле FTB, добавки команде нет
        Limits lim = Limits.of(p.settings, land, teams.members(BARSUKI), teams.leader(BARSUKI), p.data::bonus);
        assertEquals(510, lim.claims);
        assertEquals(65, lim.force);
        assertEquals(0, land.bonusClaims);
        assertEquals(0, land.bonusForce);
    }

    @Test
    void playersAlreadyInRysTeamGiveLandToIt() throws IOException {
        FtbWorld w = samples();
        UUID other = UUID.fromString("dddddddd-0000-0000-0000-000000000001");
        FakeTeams teams = new FakeTeams().team(other, A, B);
        FtbClaims.Plan p = FtbClaims.plan(w, new Settings(), true, teams, List.of(other));
        assertSums(w, p, teams);
        assertEquals(other, p.target.get(BARSUKI));
        assertEquals(6, p.data.claimed(other));
        assertEquals(0, p.data.claimed(BARSUKI));
        assertEquals(other, p.data.owner("minecraft:overworld", Chunks.pack(10, 20)));
    }

    @Test
    void serverTeamAndExpiry() throws IOException {
        FtbWorld w = samples();
        FtbClaims.Plan p = FtbClaims.plan(w, new Settings(), true, new FakeTeams(), List.of());
        TeamLand server = p.data.land(SERVER);
        assertEquals("Spawn", server.legacyName);
        assertEquals("ftb-server", server.legacySource);
        assertEquals(SERVER, p.data.owner("minecraft:overworld", Chunks.pack(0, 0)));
        assertTrue(p.report.lines().stream().anyMatch(l -> l.contains("FTB expiry")), p.report.lines().toString());
    }

    @Test
    void brokenFileDoesNotStopOthers(@TempDir Path tmp) throws IOException {
        Path root = tmp.resolve("ftb");
        copy(FtbFilesTest.samples(), root);
        Files.writeString(root.resolve("world/ftbchunks/eeeeeeee-0000-0000-0000-000000000001.snbt"), "{ chunks: { \"minecraft:overworld\": [ { x: 1, z: ");
        FtbWorld w = FtbFilesTest.read(root);
        assertEquals(1, w.errors.size());
        FtbClaims.Plan p = FtbClaims.plan(w, new Settings(), true, new FakeTeams(), List.of());
        assertTrue(p.report.isPartial());
        assertEquals(6, p.data.claimed(BARSUKI));
        assertEquals(9, p.data.total());
    }

    @Test
    void threeRunsGiveTheSame() throws IOException {
        FakeTeams teams = new FakeTeams().team(BARSUKI, A, B);
        Set<String> outputs = new HashSet<>();
        for (int i = 0; i < 3; i++) {
            FtbClaims.Plan p = FtbClaims.plan(samples(), new Settings(), true, teams, List.of(BARSUKI));
            ClaimData live = new ClaimData(p.settings.defaultRights);
            live.replaceWith(p.data);
            outputs.add(live.save().toString());
        }
        assertEquals(1, outputs.size());
    }

    @Test
    void settingsAreNotAppliedWhenOurFileWasEdited() throws IOException {
        Settings mine = new Settings();
        mine.forcePerTeam = 7;
        FtbClaims.Plan p = FtbClaims.plan(samples(), mine, false, new FakeTeams().team(BARSUKI, A, B), List.of(BARSUKI));
        assertEquals(7, p.settings.forcePerTeam);
        // лимит всё равно не меньше FTB: разницу закрыла добавка команде
        TeamLand land = p.data.land(BARSUKI);
        assertEquals(65 - (7 + 40), land.bonusForce);
    }

    @Test
    void realRepysFiles() throws IOException {
        Path root = Path.of(System.getProperty("rysclaims.fixtures", "../../_shared/_saved/fixtures/ftb-2026-11-18"));
        assumeTrue(Files.isDirectory(root.resolve("world/ftbchunks")), "нет копии файлов FTB RepYS: " + root);
        FtbWorld w = FtbFilesTest.read(root);
        // RYSTeams на сервере автора уже знает команды FTB с теми же UUID и составом
        FakeTeams teams = new FakeTeams();
        List<UUID> known = new java.util.ArrayList<>();
        for (FtbWorld.Team t : w.teams.values()) {
            if (!"party".equals(t.type) || t.owner == null) continue;
            UUID[] others = t.members().stream().filter(m -> !m.equals(t.owner)).toArray(UUID[]::new);
            teams.team(t.id, t.owner, others);
            known.add(t.id);
        }
        FtbClaims.Plan p = FtbClaims.plan(w, new Settings(), true, teams, known);
        assertSums(w, p, teams);
        for (UUID id : known) {
            FtbWorld.Land land = w.lands.get(id);
            if (land == null || land.chunkCount() == 0) continue;
            TeamLand ours = p.data.land(id);
            assertNotNull(ours);
            Limits lim = Limits.of(p.settings, ours, teams.members(id), teams.leader(id), p.data::bonus);
            // лимит прогрузки не меньше прогруженного и не меньше лимита FTB, без добавки команде
            assertTrue(lim.force >= land.forcedCount());
            assertEquals(land.maxForce, lim.force);
            assertEquals(land.maxClaims, lim.claims);
            assertEquals(0, ours.bonusForce);
            // все четыре строки прав и переключатели как в FTB у VENIK: «команда и союз», машины по профилю
            for (Rights.Action a : Rights.Action.values()) {
                assertEquals(works.repka.rysclaims.migrate.FtbSettings.preset(
                    a == Rights.Action.BUILD ? w.teams.get(id).property("ftbchunks:block_edit_mode", "")
                        : a == Rights.Action.ENTITY ? works.repka.rysclaims.migrate.FtbSettings.stricter(
                            w.teams.get(id).property("ftbchunks:entity_interact_mode", null),
                            w.teams.get(id).property("ftbchunks:nonliving_entity_attack_mode", null))
                        : w.teams.get(id).property("ftbchunks:block_interact_mode", "")),
                    ours.rights.presetOf(a), a.key());
            }
            assertEquals(w.teams.get(id).flag("ftbchunks:allow_fake_players_by_id", true), ours.flag(TeamLand.Flag.MACHINES_PROFILE));
            assertEquals(w.teams.get(id).flag("ftbchunks:allow_fake_players", false), ours.flag(TeamLand.Flag.MACHINES_OUTSIDE));
            assertTrue(ours.flag(TeamLand.Flag.MACHINES_INSIDE));
        }
        assertEquals(Settings.BonusMode.LARGEST, p.settings.bonusMode);
    }

    static void copy(Path from, Path to) throws IOException {
        try (Stream<Path> s = Files.walk(from)) {
            for (Path src : s.toList()) {
                Path dst = to.resolve(from.relativize(src).toString());
                if (Files.isDirectory(src)) Files.createDirectories(dst);
                else Files.copy(src, dst);
            }
        }
    }
}

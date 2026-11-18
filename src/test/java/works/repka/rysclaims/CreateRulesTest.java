package works.repka.rysclaims;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import works.repka.rysclaims.data.Chunks;
import works.repka.rysclaims.data.ClaimData;
import works.repka.rysclaims.data.Rights;
import works.repka.rysclaims.data.Settings;
import works.repka.rysclaims.data.TeamLand;
import works.repka.rysclaims.server.ClaimService;
import works.repka.rysclaims.server.Machines;

/**
 * Правила З5 для Create без игры: постройка судится по месту якоря (блока управления), хозяина у неё нет; рука и
 * воронка по месту своей блок-сущности; машина с профилем хозяина (установщик) по его правам.
 */
class CreateRulesTest {
    static final String OW = "minecraft:overworld";
    static final UUID TEAM = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
    static final UUID OTHER = UUID.fromString("00000000-0000-0000-0000-0000000000bb");
    static final UUID LEADER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    static final UUID STRANGER = UUID.fromString("00000000-0000-0000-0000-000000000004");

    Settings settings;
    ClaimService service;
    final long own = Chunks.pack(0, 0), own2 = Chunks.pack(1, 0), foreign = Chunks.pack(2, 0), wild = Chunks.pack(5, 5);

    @BeforeEach
    void setUp() {
        settings = new Settings();
        FakeTeams teams = new FakeTeams().team(TEAM, LEADER).team(OTHER, STRANGER);
        service = new ClaimService(new ClaimData(settings.defaultRights), () -> settings, teams, () -> 0L, new ClaimService.Hooks() {});
        service.claim(null, TEAM, OW, LongArrayList.of(own, own2), true);
        service.claim(null, OTHER, OW, LongArrayList.of(foreign), true);
    }

    private boolean contraption(long anchor, long target) {
        return service.canMachine(OW, anchor, null, target, Rights.Action.BUILD, Machines.FAR);
    }

    @Test
    void contraptionWorksOnItsLandAndWild() {
        assertTrue(contraption(own, own2), "бур постройки на своей земле копает");
        assertTrue(contraption(own, wild), "на свободной земле копает");
        assertTrue(contraption(wild, wild), "постройка со свободной земли на свободной");
    }

    @Test
    void contraptionStopsAtOtherTeam() {
        assertFalse(contraption(own2, foreign), "бур через границу не копает, сборка не берёт чужой блок, разборка не ставит");
        // поршень в own2, конец штока (anchor постройки) уже в чужом чанке: судит блок управления, а не anchor
        assertFalse(contraption(own2, foreign));
        // постройка, собранная на свободной земле: на земле команды как машина снаружи
        assertFalse(contraption(wild, own));
        service.data().landOrCreate(TEAM).flag(TeamLand.Flag.MACHINES_OUTSIDE, true);
        assertTrue(contraption(wild, own), "«Машины снаружи» хозяин земли включил сам");
    }

    @Test
    void ownerProfileDecidesForDeployer() {
        // установщик: фейковый игрок с UUID хозяина, место неизвестно
        assertTrue(service.canMachine(OW, Long.MIN_VALUE, LEADER, own, Rights.Action.BUILD));
        assertFalse(service.canMachine(OW, Long.MIN_VALUE, LEADER, foreign, Rights.Action.BUILD));
        assertTrue(service.canMachine(OW, Long.MIN_VALUE, STRANGER, foreign, Rights.Action.BUILD));
    }

    @Test
    void armOnlyOwnSideOfBorder() {
        assertTrue(contraption(own, own2), "рука у своей границы берёт из своего сундука");
        assertFalse(contraption(own2, foreign), "из сундука соседа за границей нет");
    }
}

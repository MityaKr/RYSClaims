package works.repka.rysclaims;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import works.repka.rysclaims.server.ClaimService.Reason;

/**
 * Правила З2, часть 2, без игры: жидкость, огонь и поршень через границу, взрыв без хозяина и его настройка, причина отказа,
 * дальность машины по месту.
 */
class BordersTest {
    static final String OW = "minecraft:overworld";
    static final UUID TEAM = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
    static final UUID OTHER = UUID.fromString("00000000-0000-0000-0000-0000000000bb");
    static final UUID LEADER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    static final UUID GUEST = UUID.fromString("00000000-0000-0000-0000-000000000003");
    static final UUID STRANGER = UUID.fromString("00000000-0000-0000-0000-000000000004");

    Settings settings;
    ClaimService service;
    final long a = Chunks.pack(0, 0), a2 = Chunks.pack(1, 0), b = Chunks.pack(2, 0), wild = Chunks.pack(5, 5);

    @BeforeEach
    void setUp() {
        settings = new Settings();
        FakeTeams teams = new FakeTeams().team(TEAM, LEADER).join(TEAM, GUEST, "guest").team(OTHER, STRANGER);
        ClaimData data = new ClaimData(settings.defaultRights);
        service = new ClaimService(data, () -> settings, teams, () -> 0L, new ClaimService.Hooks() {});
        service.claim(null, TEAM, OW, LongArrayList.of(a, a2), true);
        service.claim(null, OTHER, OW, LongArrayList.of(b), true);
    }

    @Test
    void fluidAndFireStayOnOneTeamLand() {
        assertTrue(service.mayCross(OW, a, a, false), "тот же чанк");
        assertTrue(service.mayCross(OW, a, a2, false), "своя земля в соседнем чанке");
        assertTrue(service.mayCross(OW, a, wild, false), "на свободную землю можно");
        assertFalse(service.mayCross(OW, a2, b, false), "на землю другой команды нельзя");
        assertFalse(service.mayCross(OW, wild, a, false), "со свободной земли на землю команды нельзя");
        assertTrue(service.mayCross(OW, a2, b, true), "настройка сервера разрешает");
        settings.protection = false;
        assertTrue(service.mayCross(OW, a2, b, false), "защита выключена");
    }

    @Test
    void ownerlessExplosionBySetting() {
        assertTrue(service.ownerlessExplosion(OW, a, wild), "свободная земля");
        assertFalse(service.ownerlessExplosion(OW, a, a2), "своя земля без переключателя");
        service.data().landOrCreate(TEAM).flag(TeamLand.Flag.OWN_EXPLOSIONS, true);
        assertTrue(service.ownerlessExplosion(OW, a, a2), "своя земля с переключателем");
        assertFalse(service.ownerlessExplosion(OW, wild, a2), "центр на свободной земле");
        assertFalse(service.ownerlessExplosion(OW, b, a2), "центр на земле другой команды");
        settings.ownerless = Settings.Ownerless.NEVER;
        assertFalse(service.ownerlessExplosion(OW, a, a2), "никогда");
        assertTrue(service.ownerlessExplosion(OW, a, wild), "свободная земля и при «никогда»");
        settings.ownerless = Settings.Ownerless.ALWAYS;
        assertTrue(service.ownerlessExplosion(OW, b, a2), "всегда");
    }

    @Test
    void reasons() {
        assertEquals(Reason.BUILD, service.reason(STRANGER, OW, a, Rights.Action.BUILD));
        assertEquals(Reason.OPEN, service.reason(STRANGER, OW, a, Rights.Action.OPEN));
        assertEquals(Reason.GUEST, service.reason(GUEST, OW, a, Rights.Action.BUILD));
        assertEquals(Reason.WILDERNESS, service.reason(STRANGER, OW, wild, Rights.Action.BUILD));
        for (Reason r : Reason.values()) assertEquals(r, Reason.parse(r.key()));
    }

    @Test
    void machineReachByPlace() {
        long far = Chunks.pack(6, 0);
        service.claim(null, TEAM, OW, LongArrayList.of(far), true);
        // машина в чанке a, цель своей земли в 6 чанках: обычная дальность 2 не достаёт, блок-сущность с дальним действием достаёт
        assertFalse(service.canMachine(OW, a, null, far, Rights.Action.BUILD));
        assertTrue(service.canMachine(OW, a, null, far, Rights.Action.BUILD, Integer.MAX_VALUE));
        // через границу нельзя на любой дальности
        assertFalse(service.canMachine(OW, a2, null, b, Rights.Action.BUILD, Integer.MAX_VALUE));
        // место неизвестно: «Машины снаружи»
        assertFalse(service.canMachine(OW, Long.MIN_VALUE, null, b, Rights.Action.BUILD, Integer.MAX_VALUE));
        service.data().landOrCreate(OTHER).flag(TeamLand.Flag.MACHINES_OUTSIDE, true);
        assertTrue(service.canMachine(OW, Long.MIN_VALUE, null, b, Rights.Action.BUILD, Integer.MAX_VALUE));
    }
}

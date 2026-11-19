package works.repka.rysclaims;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import works.repka.rysclaims.data.Chunks;
import works.repka.rysclaims.data.ClaimData;
import works.repka.rysclaims.data.FakeProfiles;
import works.repka.rysclaims.data.Rights;
import works.repka.rysclaims.data.Settings;
import works.repka.rysclaims.data.TeamLand;
import works.repka.rysclaims.server.ClaimService;

/**
 * Фейковые игроки без игры (разбор фейков 20.11): кто хозяин фейка и какое правило решает. Место машины здесь это чанк
 * тикающей блок-сущности ({@code from}), в игре его даёт {@code BlockEntityTickMixin}.
 */
class FakePlayersTest {
    static final String OW = "minecraft:overworld";
    static final UUID TEAM = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
    static final UUID OTHER = UUID.fromString("00000000-0000-0000-0000-0000000000bb");
    static final UUID LEADER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    static final UUID STRANGER = UUID.fromString("00000000-0000-0000-0000-000000000004");
    static final UUID FRIEND = UUID.fromString("00000000-0000-0000-0000-000000000005");
    /** {@code FakePlayerFactory.getMinecraft}: так ставит и ломает Actually Additions. */
    static final UUID MINECRAFT = UUID.fromString("41C82C87-7AfB-4024-BA57-13D2C99CAE77");
    static final long UNKNOWN = Long.MIN_VALUE;

    Settings settings;
    ClaimService service;
    final long own = Chunks.pack(0, 0), own2 = Chunks.pack(1, 0), far = Chunks.pack(40, 0), foreign = Chunks.pack(2, 0),
        wild = Chunks.pack(5, 5);

    @BeforeEach
    void setUp() {
        settings = new Settings();
        FakeTeams teams = new FakeTeams().team(TEAM, LEADER).team(OTHER, STRANGER).team(UUID.randomUUID(), FRIEND);
        service = new ClaimService(new ClaimData(settings.defaultRights), () -> settings, teams, () -> 0L, new ClaimService.Hooks() {});
        service.claim(null, TEAM, OW, LongArrayList.of(own, own2, far), true);
        service.claim(null, OTHER, OW, LongArrayList.of(foreign), true);
    }

    private boolean fake(long from, UUID uuid, long target) {
        return service.canFake(OW, from, FakeProfiles.owner(uuid), "[Minecraft]", target, Rights.Action.BUILD);
    }

    // ---- кто хозяин ----

    @Test
    void sharedProfilesHaveNoOwner() {
        assertNull(FakeProfiles.owner(MINECRAFT), "[Minecraft] у Actually Additions");
        assertNull(FakeProfiles.owner(UUID.fromString("99562b85-bd1a-4ded-bb1a-c307bf0c0133")), "Immersive Engineering");
        assertNull(FakeProfiles.owner(UUID.fromString("41C82C87-7AfB-4024-BB57-13D2C99CAE77")), "Integrated Tunnels");
        assertNull(FakeProfiles.owner(UUID.fromString("46e82cd0-d480-3d48-800a-77431ede078e")), "Mekanism без хозяина");
        assertNull(FakeProfiles.owner(UUID.fromString("9e2faded-cafe-4ec2-c314-dad129ae971d")), "установщик Create без хозяина");
        assertNull(FakeProfiles.owner(UUID.nameUUIDFromBytes("sophisticated_core_fake_player".getBytes(StandardCharsets.UTF_8))));
        assertNull(FakeProfiles.owner(null));
    }

    @Test
    void ownerUuidIsTheOwner() {
        // установщик Create и шахтёр Mekanism отдают getUUID() хозяина машины
        assertEquals(LEADER, FakeProfiles.owner(LEADER));
        assertTrue(FakeProfiles.shared(MINECRAFT));
        assertFalse(FakeProfiles.shared(LEADER));
    }

    // ---- какое правило ----

    @Test
    void ownerlessMachineWorksOnOwnLand() {
        assertTrue(fake(own, MINECRAFT, own), "ломатель в своём чанке");
        assertTrue(fake(own, MINECRAFT, own2), "установщик у своей границы ставит в соседний свой чанк");
        assertTrue(fake(own, MINECRAFT, far), "месту блок-сущности верим на любом расстоянии своей земли");
    }

    @Test
    void ownerlessMachineStopsAtBorder() {
        assertFalse(fake(own2, MINECRAFT, foreign), "со своей земли в чужую не ломает");
        assertFalse(fake(wild, MINECRAFT, own), "со свободной земли в нашу не ломает");
        assertFalse(fake(foreign, MINECRAFT, own), "сосед из-за границы не ломает");
        assertTrue(fake(own, MINECRAFT, wild), "на свободной земле можно");
    }

    @Test
    void ownerlessMachineWithUnknownPlaceNeedsMachinesOutside() {
        assertFalse(fake(UNKNOWN, MINECRAFT, own), "место неизвестно: как машина снаружи");
        service.data().landOrCreate(TEAM).flag(TeamLand.Flag.MACHINES_OUTSIDE, true);
        assertTrue(fake(UNKNOWN, MINECRAFT, own), "«Машины снаружи» включены: можно всем машинам");
        assertTrue(fake(foreign, MINECRAFT, own));
    }

    @Test
    void machinesSettingThreeWays() {
        TeamLand land = service.data().landOrCreate(TEAM);
        // «только своя земля» по умолчанию
        assertTrue(fake(own, MINECRAFT, own2));
        assertFalse(fake(wild, MINECRAFT, own));
        // «запретить»: обе выключены
        land.flag(TeamLand.Flag.MACHINES_INSIDE, false);
        assertFalse(fake(own, MINECRAFT, own2), "машины без хозяина на земле стоят");
        assertTrue(fake(own, LEADER, own2), "машина с хозяином-участником всё равно работает по его правам");
        // «разрешить»: обе включены
        land.flag(TeamLand.Flag.MACHINES_INSIDE, true);
        land.flag(TeamLand.Flag.MACHINES_OUTSIDE, true);
        assertTrue(fake(wild, MINECRAFT, own));
    }

    @Test
    void ownerActsWithOwnerRights() {
        assertTrue(fake(UNKNOWN, LEADER, own), "установщик хозяина на своей земле, место неизвестно");
        assertFalse(fake(UNKNOWN, LEADER, foreign), "на чужой нет");
        assertFalse(fake(own2, LEADER, foreign), "и из-за своей границы нет");
        assertFalse(fake(UNKNOWN, STRANGER, own), "чужой установщик на нашей земле нет");
        service.trust(TEAM, FRIEND, true);
        assertEquals(service.can(FRIEND, OW, own, Rights.Action.BUILD), fake(UNKNOWN, FRIEND, own), "машине доверенного столько же, сколько ему");
        service.data().landOrCreate(TEAM).rights.set(Rights.Action.BUILD, Rights.ALLIES, true);
        assertTrue(service.can(FRIEND, OW, own, Rights.Action.BUILD));
        assertTrue(fake(UNKNOWN, FRIEND, own), "доверенному можно строить: его машине тоже");
        assertFalse(fake(UNKNOWN, FRIEND, foreign), "а на чужой земле нет");
    }

    @Test
    void ownerProfileSwitchOff() {
        service.data().landOrCreate(TEAM).flag(TeamLand.Flag.MACHINES_PROFILE, false);
        assertFalse(fake(UNKNOWN, LEADER, own), "без «Машины с профилем» хозяин не в счёт, место неизвестно");
        assertTrue(fake(own, LEADER, own2), "место своё: решает «Машины на земле команды»");
    }

    @Test
    void namedListAndWilderness() {
        service.data().landOrCreate(TEAM).machineNames.add("[Minecraft]");
        assertTrue(fake(UNKNOWN, MINECRAFT, own), "имя в списке команды");
        assertFalse(fake(UNKNOWN, MINECRAFT, foreign), "список своей команды на чужую землю не действует");
        settings.noWilderness = true;
        assertFalse(service.canFake(OW, own, null, "[Minecraft]", wild, Rights.Action.BUILD), "закрытая свободная земля машинам без хозяина");
    }

    @Test
    void protectionOffAllowsAll() {
        settings.protection = false;
        assertTrue(fake(wild, MINECRAFT, own));
    }
}

package works.repka.rysclaims;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongList;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import works.repka.rysclaims.data.Chunks;
import works.repka.rysclaims.data.ClaimData;
import works.repka.rysclaims.data.Rights;
import works.repka.rysclaims.data.Settings;
import works.repka.rysclaims.data.TeamLand;
import works.repka.rysclaims.server.ClaimService;
import works.repka.rysclaims.server.ClaimService.Skip;

/** Сервис земли без игры: занять, лимиты, освобождение с отсчётом, прогрузка, права, события команд. */
class ClaimServiceTest {
    static final String OW = "minecraft:overworld";
    static final UUID TEAM = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
    static final UUID OTHER = UUID.fromString("00000000-0000-0000-0000-0000000000bb");
    static final UUID LEADER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    static final UUID MEMBER = UUID.fromString("00000000-0000-0000-0000-000000000002");
    static final UUID GUEST = UUID.fromString("00000000-0000-0000-0000-000000000003");
    static final UUID STRANGER = UUID.fromString("00000000-0000-0000-0000-000000000004");
    static final UUID ALLY = UUID.fromString("00000000-0000-0000-0000-000000000005");

    Settings settings;
    FakeTeams teams;
    ClaimData data;
    long now;
    final List<String> events = new ArrayList<>();
    ClaimService service;

    @BeforeEach
    void setUp() {
        settings = new Settings();
        teams = new FakeTeams().team(TEAM, LEADER, MEMBER).join(TEAM, GUEST, "guest").team(OTHER, ALLY)
            .perm(ClaimService.PERM_CLAIM, "leader", "officer", "member").perm(ClaimService.PERM_RIGHTS, "leader");
        data = new ClaimData(settings.defaultRights);
        now = 1_000_000L;
        service = new ClaimService(data, () -> settings, teams, () -> now, new ClaimService.Hooks() {
            @Override
            public void claimed(UUID team, String dimension, LongList chunks) {
                events.add("claimed " + chunks.size());
            }

            @Override
            public void released(UUID team, String dimension, LongList chunks) {
                events.add("released " + chunks.size());
            }

            @Override
            public void accessChanged() {
                events.add("access");
            }
        });
    }

    /** З6: каждая правка, от которой зависит доступ, поднимает версию прав (рассылка клиентам). */
    @Test
    void accessEditsRaiseVersion() {
        service.trust(TEAM, STRANGER, true);
        assertEquals(Rights.Relation.ALLY, service.relation(STRANGER, TEAM));
        assertFalse(service.trust(TEAM, STRANGER, true), "повтор ничего не меняет");
        service.setFlag(TEAM, TeamLand.Flag.PVP, false);
        service.applyPreset(TEAM, Rights.Preset.OPEN);
        service.toggleRight(TEAM, Rights.Action.BUILD, Rights.Column.ALL, false);
        service.toggleBypass(STRANGER);
        assertEquals(List.of("access", "access", "access", "access", "access"), events);
        assertTrue(service.allows(STRANGER, OTHER, Rights.Action.BUILD), "оператор");
    }

    static LongList one(int x, int z) {
        return LongArrayList.of(Chunks.pack(x, z));
    }

    @Test
    void claimSquareAndOrderFromCenter() {
        LongList sq = Chunks.square(5, 5, 1);
        assertEquals(9, sq.size());
        assertEquals(Chunks.pack(5, 5), sq.getLong(0));
        ClaimService.Result r = service.claim(LEADER, TEAM, OW, sq, false);
        assertEquals(9, r.done);
        assertEquals(9, data.claimed(TEAM));
        assertEquals(TEAM, data.owner(OW, Chunks.pack(4, 6)));
        assertEquals(List.of("claimed 9"), events);
        // повтор: уже свои
        assertEquals(9, service.claim(LEADER, TEAM, OW, sq, false).skipped.get(Skip.ALREADY));
        // радиус 15 укладывается в потолок правки
        assertEquals(961, Chunks.square(0, 0, 15).size());
        assertEquals(961, new it.unimi.dsi.fastutil.longs.LongOpenHashSet(Chunks.square(0, 0, 15)).size());
    }

    @Test
    void limitClaimsNearestFirst() {
        settings.claimsPerTeam = 5;
        ClaimService.Result r = service.claim(LEADER, TEAM, OW, Chunks.square(0, 0, 1), false);
        assertEquals(5, r.done);
        assertEquals(4, r.skipped.get(Skip.LIMIT));
        assertEquals(TEAM, data.owner(OW, Chunks.pack(0, 0)));
        // личная добавка участника в режиме «наибольшая» открывает лимит
        service.addPlayerBonus(MEMBER, 3, 0);
        assertEquals(8, service.limits(TEAM).claims);
        assertEquals(3, service.claim(LEADER, TEAM, OW, Chunks.square(0, 0, 1), false).done);
        // оператор идёт мимо лимита
        assertEquals(25 - 8, service.claim(null, TEAM, OW, Chunks.square(0, 0, 2), true).done);
        assertEquals(25, data.claimed(TEAM));
    }

    @Test
    void otherTeamRightsAndDimension() {
        service.claim(ALLY, OTHER, OW, one(1, 1), false);
        ClaimService.Result r = service.claim(LEADER, TEAM, OW, Chunks.square(1, 1, 0), false);
        assertEquals(1, r.skipped.get(Skip.OTHER_TEAM));
        // гость без права занимать
        assertEquals(1, service.claim(GUEST, TEAM, OW, one(9, 9), false).skipped.get(Skip.NO_RIGHT));
        // игрок не из этой команды не может занять ей землю
        assertEquals(1, service.claim(STRANGER, TEAM, OW, one(9, 9), false).skipped.get(Skip.NO_RIGHT));
        // измерения без привата, в том числе по маске мода
        assertEquals(1, service.claim(LEADER, TEAM, "ae2:spatial_storage", one(0, 0), false).skipped.get(Skip.DIMENSION));
        settings.disabledDimensions.add("twilight:*");
        assertEquals(1, service.claim(LEADER, TEAM, "twilight:forest", one(0, 0), false).skipped.get(Skip.DIMENSION));
        settings.onlyDimensions.add(OW);
        assertEquals(1, service.claim(LEADER, TEAM, "minecraft:the_nether", one(0, 0), false).skipped.get(Skip.DIMENSION));
        assertEquals(1, service.claim(LEADER, TEAM, OW, one(0, 0), false).done);
    }

    @Test
    void releaseCountdownKeepAndTick() {
        service.claim(LEADER, TEAM, OW, Chunks.square(0, 0, 1), false);
        service.load(LEADER, TEAM, OW, one(0, 0), true, false);
        events.clear();
        ClaimService.Result r = service.release(MEMBER, TEAM, OW, Chunks.square(0, 0, 1), false);
        assertEquals(9, r.done);
        // отсчёт идёт: земля, прогрузка и лимит держатся, чужой занять не может
        assertEquals(9, data.claimed(TEAM));
        assertEquals(1, data.forced(TEAM));
        assertEquals(1, service.claim(ALLY, OTHER, OW, one(1, 1), false).skipped.get(Skip.OTHER_TEAM));
        assertFalse(service.can(STRANGER, OW, Chunks.pack(0, 0), Rights.Action.BUILD));
        // на освобождаемом чанке прогрузку не включить
        assertEquals(1, service.load(LEADER, TEAM, OW, one(1, 0), true, false).skipped.get(Skip.RELEASING));
        now += 10_000;
        service.tick();
        assertEquals(9, data.claimed(TEAM));
        // «Оставить» отменяет всё
        assertEquals(9, service.keep(LEADER, TEAM, false));
        now += 60_000;
        service.tick();
        assertEquals(9, data.claimed(TEAM));
        // снова освобождаем и ждём конца
        service.release(LEADER, TEAM, OW, Chunks.square(0, 0, 1), false);
        now += 29_999;
        service.tick();
        assertEquals(9, data.claimed(TEAM));
        now += 1;
        service.tick();
        assertEquals(0, data.claimed(TEAM));
        assertEquals(0, data.forced(TEAM));
        assertNull(data.owner(OW, Chunks.pack(0, 0)));
        assertEquals(List.of("released 9"), events);
        // гость не освобождает
        service.claim(LEADER, TEAM, OW, one(3, 3), false);
        assertEquals(1, service.release(GUEST, TEAM, OW, one(3, 3), false).skipped.get(Skip.NO_RIGHT));
        assertEquals(-1, service.keep(GUEST, TEAM, false));
    }

    @Test
    void releaseNowWhenNoCountdownAndReclaimKeeps() {
        service.claim(LEADER, TEAM, OW, one(0, 0), false);
        service.release(LEADER, TEAM, OW, one(0, 0), false);
        // занять свой освобождаемый чанк значит оставить его
        assertEquals(1, service.claim(LEADER, TEAM, OW, one(0, 0), false).done);
        assertFalse(data.get(OW, Chunks.pack(0, 0)).releasing());
        settings.releaseSeconds = 0;
        service.release(LEADER, TEAM, OW, one(0, 0), false);
        assertEquals(0, data.claimed(TEAM));
    }

    @Test
    void releaseSurvivesReload() {
        service.claim(LEADER, TEAM, OW, one(0, 0), false);
        service.release(LEADER, TEAM, OW, one(0, 0), false);
        ClaimData loaded = ClaimData.load(data.save(), settings.defaultRights);
        ClaimService again = new ClaimService(loaded, () -> settings, teams, () -> now + 31_000, new ClaimService.Hooks() {});
        again.tick();
        assertEquals(0, loaded.claimed(TEAM));
    }

    @Test
    void forceLimitAndOwnership() {
        settings.forcePerTeam = 2;
        service.claim(LEADER, TEAM, OW, Chunks.square(0, 0, 1), false);
        ClaimService.Result r = service.load(MEMBER, TEAM, OW, Chunks.square(0, 0, 1), true, false);
        assertEquals(2, r.done);
        assertEquals(7, r.skipped.get(Skip.FORCE_LIMIT));
        assertEquals(1, service.load(LEADER, TEAM, OW, one(50, 50), true, false).skipped.get(Skip.NOT_CLAIMED));
        service.claim(ALLY, OTHER, OW, one(50, 50), false);
        assertEquals(1, service.load(LEADER, TEAM, OW, one(50, 50), true, false).skipped.get(Skip.NOT_OWNER));
        assertEquals(2, service.load(LEADER, TEAM, OW, Chunks.square(0, 0, 1), false, false).done);
        assertEquals(0, data.forced(TEAM));
        // личная добавка прогрузки
        service.addPlayerBonus(LEADER, 0, 5);
        assertEquals(7, service.limits(TEAM).force);
        assertEquals(7, service.load(LEADER, TEAM, OW, Chunks.square(0, 0, 1), true, false).done);
    }

    @Test
    void adjacentOnly() {
        settings.adjacentOnly = true;
        assertEquals(1, service.claim(LEADER, TEAM, OW, one(0, 0), false).done);
        assertEquals(1, service.claim(LEADER, TEAM, OW, one(5, 5), false).skipped.get(Skip.NOT_ADJACENT));
        // цепочка в одном выделении растёт от своей земли, даже если идёт не по порядку
        LongList chain = LongArrayList.of(Chunks.pack(3, 0), Chunks.pack(2, 0), Chunks.pack(1, 0));
        assertEquals(3, service.claim(LEADER, TEAM, OW, chain, false).done);
        // касание углом не считается
        assertEquals(1, service.claim(LEADER, TEAM, OW, one(4, 1), false).skipped.get(Skip.NOT_ADJACENT));
    }

    @Test
    void accessByRelation() {
        service.claim(LEADER, TEAM, OW, one(0, 0), false);
        long c = Chunks.pack(0, 0);
        // по умолчанию: строить команда, открывать и двери команда и союз
        assertTrue(service.can(LEADER, OW, c, Rights.Action.BUILD));
        assertTrue(service.can(MEMBER, OW, c, Rights.Action.BUILD));
        assertFalse(service.can(GUEST, OW, c, Rights.Action.BUILD));
        assertTrue(service.can(GUEST, OW, c, Rights.Action.OPEN)); // гость получает столбец «Союзники»
        assertFalse(service.can(STRANGER, OW, c, Rights.Action.OPEN));
        assertFalse(service.can(ALLY, OW, c, Rights.Action.OPEN));
        teams.ally(TEAM, OTHER);
        assertTrue(service.can(ALLY, OW, c, Rights.Action.OPEN));
        assertFalse(service.can(ALLY, OW, c, Rights.Action.BUILD));
        // доверенный игрок это столбец «Союзники»
        data.landOrCreate(TEAM).trusted.add(STRANGER);
        assertTrue(service.can(STRANGER, OW, c, Rights.Action.USE));
        // настройка сервера «Союзники: никто»
        settings.allyMode = Settings.AllyMode.NONE;
        assertFalse(service.can(STRANGER, OW, c, Rights.Action.USE));
        assertFalse(service.can(ALLY, OW, c, Rights.Action.USE));
        settings.allyMode = Settings.AllyMode.ALL;
        UUID random = UUID.randomUUID();
        assertTrue(service.can(random, OW, c, Rights.Action.USE));
        settings.allyMode = Settings.AllyMode.TEAM;
        // открытая база
        data.landOrCreate(TEAM).rights.apply(Rights.Preset.OPEN);
        assertTrue(service.can(random, OW, c, Rights.Action.BUILD));
        // режим оператора и выключенная защита
        data.landOrCreate(TEAM).rights.apply(Rights.Preset.TEAM);
        assertFalse(service.can(random, OW, c, Rights.Action.BUILD));
        assertTrue(service.toggleBypass(random));
        assertTrue(service.can(random, OW, c, Rights.Action.BUILD));
        assertFalse(service.toggleBypass(random));
        settings.protection = false;
        assertTrue(service.can(random, OW, c, Rights.Action.BUILD));
    }

    @Test
    void wilderness() {
        long free = Chunks.pack(100, 100);
        assertTrue(service.can(STRANGER, OW, free, Rights.Action.BUILD));
        settings.noWilderness = true;
        assertFalse(service.can(STRANGER, OW, free, Rights.Action.BUILD));
        settings.noWildernessDimensions.add("minecraft:the_nether");
        assertTrue(service.can(STRANGER, OW, free, Rights.Action.BUILD)); // список главнее общего флага
        assertFalse(service.can(STRANGER, "minecraft:the_nether", free, Rights.Action.BUILD));
    }

    @Test
    void machines() {
        service.claim(LEADER, TEAM, OW, Chunks.square(0, 0, 3), false);
        long target = Chunks.pack(0, 0);
        // профиль участника действует с его правами
        assertTrue(service.canMachine(OW, Long.MIN_VALUE, MEMBER, target, Rights.Action.BUILD));
        // общий профиль без места: «Машины снаружи» выключены
        assertFalse(service.canMachine(OW, Long.MIN_VALUE, STRANGER, target, Rights.Action.BUILD));
        assertFalse(service.canMachine(OW, Long.MIN_VALUE, null, target, Rights.Action.BUILD));
        // машина на своей земле в двух чанках от цели работает, в трёх нет
        assertTrue(service.canMachine(OW, Chunks.pack(2, 2), null, target, Rights.Action.BUILD));
        assertFalse(service.canMachine(OW, Chunks.pack(3, 0), null, target, Rights.Action.BUILD));
        // машина с чужой земли через границу не работает
        service.claim(ALLY, OTHER, OW, one(4, 0), false);
        assertFalse(service.canMachine(OW, Chunks.pack(4, 0), null, Chunks.pack(3, 0), Rights.Action.BUILD));
        // переключатели команды
        TeamLand land = data.land(TEAM);
        land.flag(TeamLand.Flag.MACHINES_INSIDE, false);
        assertFalse(service.canMachine(OW, Chunks.pack(1, 1), null, target, Rights.Action.BUILD));
        land.flag(TeamLand.Flag.MACHINES_OUTSIDE, true);
        assertTrue(service.canMachine(OW, Long.MIN_VALUE, null, target, Rights.Action.BUILD));
        land.flag(TeamLand.Flag.MACHINES_OUTSIDE, false);
        land.flag(TeamLand.Flag.MACHINES_PROFILE, false);
        assertFalse(service.canMachine(OW, Long.MIN_VALUE, MEMBER, target, Rights.Action.BUILD));
        // на свободной земле машине можно
        assertTrue(service.canMachine(OW, Long.MIN_VALUE, null, Chunks.pack(90, 90), Rights.Action.BUILD));
    }

    @Test
    void joinMovesPersonalLandIfLimitAllows() {
        UUID newbie = UUID.fromString("00000000-0000-0000-0000-000000000009");
        service.claim(newbie, newbie, OW, Chunks.square(20, 20, 1), false);
        assertEquals(9, data.claimed(newbie));
        settings.claimsPerTeam = 12;
        service.claim(LEADER, TEAM, OW, Chunks.square(0, 0, 1), false); // 9 из 12
        teams.join(TEAM, newbie, "member");
        assertFalse(service.joined(TEAM, newbie)); // 9 + 9 > 12: земля остаётся личной
        assertEquals(9, data.claimed(newbie));
        settings.claimsPerTeam = 18;
        assertTrue(service.joined(TEAM, newbie));
        assertEquals(18, data.claimed(TEAM));
        assertEquals(0, data.claimed(newbie));
        assertEquals(TEAM, data.owner(OW, Chunks.pack(20, 20)));
    }

    @Test
    void mergeAndUndo() {
        service.claim(ALLY, OTHER, OW, Chunks.square(10, 10, 1), false);
        service.load(ALLY, OTHER, OW, one(10, 10), true, false);
        service.addBonus(OTHER, 7, 1);
        service.claim(LEADER, TEAM, OW, one(0, 0), false);
        service.merged(OTHER, TEAM);
        assertEquals(10, data.claimed(TEAM));
        assertEquals(1, data.forced(TEAM));
        assertEquals(7, data.land(TEAM).bonusClaims);
        assertEquals(0, data.claimed(OTHER));
        service.mergeUndone(OTHER, TEAM);
        assertEquals(1, data.claimed(TEAM));
        assertEquals(9, data.claimed(OTHER));
        assertEquals(1, data.forced(OTHER));
        assertEquals(7, data.land(OTHER).bonusClaims);
        assertEquals(0, data.land(TEAM).bonusClaims);
    }

    /** Отмена слияния: чанк, занятый третьей командой, остаётся у неё; участки обеих команд с прежними номерами и именами. */
    @Test
    void mergeUndoKeepsPlotsAndThirdTeam() {
        settings.releaseSeconds = 0;
        service.claim(LEADER, TEAM, OW, Chunks.square(0, 0, 1), false); // 9, участок команды
        service.claim(ALLY, OTHER, OW, Chunks.square(3, 0, 1), false); // 9, касается стороной (x 2 и 3)
        int teamPlot = data.get(OW, Chunks.pack(0, 0)).plot(), otherPlot = data.get(OW, Chunks.pack(3, 0)).plot();
        service.renamePlot(LEADER, TEAM, teamPlot, "Дом", false);
        service.renamePlot(ALLY, OTHER, otherPlot, "Ферма", false);
        service.merged(OTHER, TEAM);
        // принимающая освобождает чанк влившейся, его занимает третья команда
        UUID third = UUID.fromString("00000000-0000-0000-0000-0000000000a3");
        service.release(LEADER, TEAM, OW, one(4, 1), false);
        service.tick();
        service.claim(null, third, OW, one(4, 1), true);
        service.mergeUndone(OTHER, TEAM);
        assertEquals(third, data.owner(OW, Chunks.pack(4, 1)), "чанк третьей команды не отнят");
        assertEquals(OTHER, data.owner(OW, Chunks.pack(3, 0)));
        assertEquals("Дом", service.plot(TEAM, data.get(OW, Chunks.pack(0, 0)).plot()).name, "имя участка принимающей на месте");
        assertEquals("Ферма", service.plot(OTHER, data.get(OW, Chunks.pack(3, 0)).plot()).name, "имя участка влившейся вернулось");
        assertEquals(teamPlot, data.get(OW, Chunks.pack(0, 0)).plot(), "номер участка принимающей прежний");
    }

    @Test
    void rightsEditing() {
        assertTrue(service.editsRights(LEADER, TEAM, false));
        assertFalse(service.editsRights(MEMBER, TEAM, false));
        assertFalse(service.editsRights(STRANGER, TEAM, false));
        settings.teamsEditRights = false;
        assertFalse(service.editsRights(LEADER, TEAM, false));
        assertTrue(service.editsRights(null, TEAM, true));
    }

    /** Возврат команды из архива снимает отсчёт распада (перепроверка 18.11, пункт 2), своё освобождение остаётся. */
    @Test
    void restoredTeamKeepsLand() {
        service.claim(LEADER, TEAM, OW, Chunks.square(0, 0, 1), false);
        service.release(LEADER, TEAM, OW, one(1, 1), false);
        assertTrue(data.get(OW, Chunks.pack(1, 1)).releasing(), "своё освобождение до распада");
        service.disbanded(TEAM, "Team");
        assertTrue(data.get(OW, Chunks.pack(0, 0)).releasing());
        now += 1000;
        assertEquals(8, service.restored(TEAM));
        assertFalse(data.get(OW, Chunks.pack(0, 0)).releasing(), "отсчёт распада снят");
        assertTrue(data.archive().isEmpty(), "запись архива ушла");
        assertEquals(0, service.restored(TEAM), "второй раз ничего");
        now += ClaimService.WEEK_MS;
        service.tick();
        assertEquals(TEAM, data.owner(OW, Chunks.pack(0, 0)), "через неделю земля на месте");
    }

    /** З11: вопрос о личной земле, «Оставить себе», «Забрать свои чанки» по полю by, распад на 7 дней, лишнее после слияния. */
    @Test
    void membershipRules() {
        UUID newbie = UUID.fromString("00000000-0000-0000-0000-000000000009");
        service.claim(newbie, newbie, OW, Chunks.square(20, 20, 1), false);
        teams.join(TEAM, newbie, "member");
        assertTrue(service.personalPending(newbie));
        service.keepPersonal(newbie);
        assertFalse(service.personalPending(newbie), "ответ «Оставить» больше не спрашивается");
        assertTrue(ClaimData.load(data.save(), settings.defaultRights).bonus(newbie).keepPersonal, "ответ переживает перезапуск");
        assertTrue(service.joined(TEAM, newbie), "передумал: «Отдать» через команду");
        assertFalse(service.personalPending(newbie));

        // принесённое из личной земли помечено; занятое для команды остаётся команде (перепроверка 18.11, пункт 1)
        service.claim(newbie, TEAM, OW, one(40, 40), false);
        assertEquals(9, service.broughtBy(TEAM, newbie).get(OW).size(), "только отданная личная земля");
        assertTrue(ClaimData.load(data.save(), settings.defaultRights).get(OW, Chunks.pack(20, 20)).brought(), "пометка переживает перезапуск");
        teams.leave(newbie);
        assertEquals(9, service.takeBack(newbie, TEAM));
        assertEquals(newbie, data.owner(OW, Chunks.pack(20, 20)));
        assertFalse(data.get(OW, Chunks.pack(20, 20)).brought(), "вернувшийся чанк снова личный");
        assertEquals(TEAM, data.owner(OW, Chunks.pack(40, 40)), "занятое для команды не забрать");

        // распад: отсчёт 7 дней, защита держится
        service.claim(LEADER, TEAM, OW, one(60, 60), false);
        service.disbanded(TEAM, "Team");
        assertTrue(data.get(OW, Chunks.pack(60, 60)).releasing());
        now += ClaimService.WEEK_MS - 1000;
        service.tick();
        assertEquals(TEAM, data.owner(OW, Chunks.pack(60, 60)), "через 6 дней земля ещё держится");
        assertEquals(1, data.archive().size());
        now += 2000;
        service.tick();
        assertNull(data.owner(OW, Chunks.pack(60, 60)));
    }

    @Test
    void overLimitAfterMergeStaysAWeek() {
        settings.claimsPerTeam = 10;
        service.claim(LEADER, TEAM, OW, Chunks.square(0, 0, 1), false); // 9
        service.claim(ALLY, OTHER, OW, Chunks.square(10, 10, 1), false); // 9
        assertEquals(8, service.mergePreview(OTHER, TEAM)[1]);
        service.merged(OTHER, TEAM);
        service.overLimitTick();
        assertTrue(data.land(TEAM).overSince > 0);
        assertEquals(0, service.claim(LEADER, TEAM, OW, one(30, 30), false).done, "сверх лимита занимать нельзя");
        now += ClaimService.WEEK_MS + 1;
        service.overLimitTick();
        assertEquals(8, service.releasing(TEAM)[0], "через неделю лишнее освобождается с отсчётом");
    }

    /** Простой команды: прогрузка снимается, за неделю предупреждение, потом земля освобождается; вход сбрасывает. */
    @Test
    void idleTeamLosesLand() {
        long day = 24L * 3600 * 1000;
        settings.idleUnforceDays = 10;
        settings.idleUnclaimDays = 30;
        service.claim(LEADER, TEAM, OW, Chunks.square(0, 0, 1), false);
        service.load(LEADER, TEAM, OW, one(0, 0), true, false);
        service.idleTick(); // первый проход: отсчёт с сейчас
        assertTrue(data.land(TEAM).lastSeen > 0);
        now += 11 * day;
        service.idleTick();
        assertEquals(0, data.forced(TEAM), "через 10 дней прогрузка снята");
        assertFalse(data.land(TEAM).idleWarned);
        now += 13 * day; // 24-й день: за неделю до снятия
        service.idleTick();
        assertTrue(data.land(TEAM).idleWarned, "предупреждение за неделю");
        assertEquals(0, service.releasing(TEAM)[0]);
        service.seen(TEAM);
        assertFalse(data.land(TEAM).idleWarned, "вход сбрасывает");
        now += 31 * day;
        service.idleTick();
        assertEquals(9, service.releasing(TEAM)[0], "через 30 дней без входа земля освобождается");
        settings.idleUnclaimDays = 0;
    }

    /** Передача и аренда участка: лимит получателя, аренда возвращается по сроку, арендатор не освобождает. */
    @Test
    void plotHandOverAndLease() {
        long day = 86_400_000L;
        service.claim(LEADER, TEAM, OW, Chunks.square(0, 0, 1), false);
        LongList plot = service.plotChunks(TEAM, data.get(OW, Chunks.pack(0, 0)).plot());
        assertEquals(9, plot.size());
        settings.claimsPerTeam = 5;
        assertEquals(-1, service.handOver(TEAM, OTHER, OW, plot, 0, "Ферма"), "у получателя нет места");
        assertEquals(TEAM, data.owner(OW, Chunks.pack(0, 0)));
        settings.claimsPerTeam = 500;
        assertEquals(9, service.handOver(TEAM, OTHER, OW, plot, now + 3 * day, "Ферма"));
        assertEquals(OTHER, data.owner(OW, Chunks.pack(0, 0)));
        assertEquals("Ферма", service.plot(OTHER, data.get(OW, Chunks.pack(0, 0)).plot()).name, "имя участка у арендатора");
        assertEquals(0, service.release(ALLY, OTHER, OW, one(0, 0), false).done, "арендатор не освобождает");
        assertEquals(1, service.leases(TEAM).size());
        assertTrue(ClaimData.load(data.save(), settings.defaultRights).leases().size() == 1, "аренда переживает перезапуск");
        assertEquals(0, service.handOver(OTHER, STRANGER, OW, plot, 0, ""), "арендатор дальше не передаёт");
        now += 3 * day + 1;
        service.leaseTick();
        assertEquals(TEAM, data.owner(OW, Chunks.pack(0, 0)), "по сроку участок вернулся");
        assertTrue(service.leases(TEAM).isEmpty());
        // насовсем
        assertEquals(9, service.handOver(TEAM, OTHER, OW, plot, 0, ""));
        assertEquals(1, service.release(ALLY, OTHER, OW, one(0, 0), false).done, "своё теперь можно освобождать");
    }

    /** Буфер: чужая команда не занимает ближе N чанков, оператор может, своя земля буфера не держит. */
    @Test
    void bufferKeepsStrangersAway() {
        settings.bufferChunks = 2;
        service.claim(LEADER, TEAM, OW, one(0, 0), false);
        var r = service.claim(ALLY, OTHER, OW, one(2, 0), false);
        assertEquals(0, r.done, "в 2 чанках нельзя");
        assertEquals(1, r.skipped.get(ClaimService.Skip.BUFFER));
        assertEquals(1, service.claim(ALLY, OTHER, OW, one(3, 0), false).done, "в 3 чанках можно");
        assertEquals(1, service.claim(LEADER, TEAM, OW, one(0, -1), false).done, "у своей земли буфера нет");
        assertEquals(0, service.claim(LEADER, TEAM, OW, one(1, 0), false).done, "буфер держит и в обратную сторону: рядом с чужой");
        assertEquals(1, service.claim(null, OTHER, OW, one(0, 2), true).done, "оператор может");
        settings.bufferChunks = 0;
    }

    /** Сниженный лимит без слияния землю не отнимает (перепроверка 18.11, пункт 3). */
    @Test
    void lowerLimitDoesNotRelease() {
        service.claim(LEADER, TEAM, OW, Chunks.square(0, 0, 1), false); // 9
        settings.claimsPerTeam = 4;
        service.overLimitTick();
        assertEquals(0, data.land(TEAM).overSince, "отсчёт не ставится");
        now += ClaimService.WEEK_MS * 2;
        service.overLimitTick();
        assertEquals(0, service.releasing(TEAM)[0], "через две недели ничего не освобождается");
        assertEquals(0, service.claim(LEADER, TEAM, OW, one(30, 30), false).done, "но занимать новое нельзя");
    }
}

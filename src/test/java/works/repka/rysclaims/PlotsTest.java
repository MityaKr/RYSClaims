package works.repka.rysclaims;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongList;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import works.repka.rysclaims.data.Chunks;
import works.repka.rysclaims.data.ClaimData;
import works.repka.rysclaims.data.Plot;
import works.repka.rysclaims.data.Plots;
import works.repka.rysclaims.data.Settings;
import works.repka.rysclaims.server.ClaimService;

/** Участки (этап З8): номер и имя, слияние, разрез, перенос при слиянии команд, старый файл без номеров. */
class PlotsTest {
    static final String OW = "minecraft:overworld";
    static final UUID TEAM = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
    static final UUID OTHER = UUID.fromString("00000000-0000-0000-0000-0000000000bb");
    static final UUID LEADER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    static final UUID LEADER2 = UUID.fromString("00000000-0000-0000-0000-000000000002");

    Settings settings;
    ClaimData data;
    ClaimService service;
    final List<Plots.Event> events = new ArrayList<>();

    @BeforeEach
    void setUp() {
        settings = new Settings();
        settings.releaseSeconds = 0;
        data = new ClaimData(settings.defaultRights);
        service = new ClaimService(data, () -> settings, new FakeTeams().team(TEAM, LEADER).team(OTHER, LEADER2), () -> 1000L,
            new ClaimService.Hooks() {
                @Override
                public void plotsChanged(UUID team, List<Plots.Event> e) {
                    events.addAll(e);
                }
            });
    }

    static LongList row(int x0, int x1, int z) {
        LongList l = new LongArrayList();
        for (int x = x0; x <= x1; x++) l.add(Chunks.pack(x, z));
        return l;
    }

    int plotAt(int x, int z) {
        return data.get(OW, Chunks.pack(x, z)).plot();
    }

    String name(int x, int z) {
        Plot p = service.plotAt(OW, Chunks.pack(x, z));
        return p == null ? null : p.name;
    }

    @Test
    void newPieceGetsPlotAndCornerIsNotTouch() {
        service.claim(LEADER, TEAM, OW, row(0, 2, 0), false);
        service.claim(LEADER, TEAM, OW, row(3, 3, 1), false); // касается углом: другой участок
        assertTrue(plotAt(0, 0) > 0);
        assertEquals(plotAt(0, 0), plotAt(2, 0));
        assertNotEquals(plotAt(0, 0), plotAt(3, 1));
        assertEquals(2, data.land(TEAM).plots.size());
        assertEquals("", name(0, 0), "новый участок без имени: «Участок N»");
    }

    @Test
    void joiningChunkKeepsNameOfLarger() {
        service.claim(LEADER, TEAM, OW, row(0, 4, 0), false);
        service.claim(LEADER, TEAM, OW, row(6, 7, 0), false);
        service.renamePlot(LEADER, TEAM, plotAt(0, 0), "База", false);
        service.renamePlot(LEADER, TEAM, plotAt(6, 0), "Шахта", false);
        events.clear();
        service.claim(LEADER, TEAM, OW, row(5, 5, 0), false);
        assertEquals("База", name(7, 0));
        assertEquals(1, data.land(TEAM).plots.size());
        assertEquals(1, events.size());
        assertEquals(Plots.Event.Kind.MERGE, events.get(0).kind());
        assertEquals("Шахта", events.get(0).other());
        assertEquals("База", events.get(0).kept());
    }

    @Test
    void releaseSplitsAndSmallerGetsNumber() {
        service.claim(LEADER, TEAM, OW, row(0, 6, 0), false);
        int base = plotAt(0, 0);
        service.renamePlot(LEADER, TEAM, base, "База", false);
        events.clear();
        service.release(LEADER, TEAM, OW, row(4, 4, 0), false); // слева 4, справа 2
        assertEquals(base, plotAt(0, 0));
        assertEquals("База", name(0, 0));
        assertEquals("База 2", name(6, 0));
        assertEquals(Plots.Event.Kind.SPLIT, events.get(0).kind());
        service.release(LEADER, TEAM, OW, row(1, 1, 0), false); // 0 | 2..3: база остаётся у большей
        assertEquals("База", name(3, 0));
        assertEquals("База 3", name(0, 0), "следующий свободный номер, а не «База 2 2»");
        service.release(LEADER, TEAM, OW, row(5, 6, 0), false);
        assertEquals(2, data.land(TEAM).plots.size(), "освобождённый целиком участок убран");
    }

    @Test
    void splitName() {
        Map<Integer, String> names = Map.of(1, "База 2");
        LongList chunks = new LongArrayList(new long[] {Chunks.pack(0, 0), Chunks.pack(0, 2), Chunks.pack(0, 3)});
        Plots.Assignment a = Plots.assign(chunks, c -> 1, names, List.of("База 2"), 5);
        assertEquals(Map.of(5, "База 3"), a.created);
        assertEquals(1, a.plot.get(Chunks.pack(0, 2)), "большая часть хранит номер");
    }

    @Test
    void mergeOfTeamsCarriesNamesAndUndoReturnsThem() {
        service.claim(LEADER, TEAM, OW, row(0, 2, 0), false);
        service.renamePlot(LEADER, TEAM, plotAt(0, 0), "База", false);
        service.claim(LEADER2, OTHER, OW, row(10, 11, 0), false);
        int theirs = plotAt(10, 0);
        service.renamePlot(LEADER2, OTHER, theirs, "Ферма", false);
        service.merged(OTHER, TEAM);
        assertEquals("Ферма", name(10, 0));
        assertEquals(2, data.land(TEAM).plots.size());
        assertTrue(data.land(OTHER).plots.isEmpty());
        service.mergeUndone(OTHER, TEAM);
        assertEquals(OTHER, data.owner(OW, Chunks.pack(10, 0)));
        assertEquals("Ферма", name(10, 0));
        assertEquals(theirs, plotAt(10, 0));
        assertEquals(1, data.land(TEAM).plots.size());
    }

    @Test
    void oldFileWithoutPlotsGetsNumbersAndKeepsThemAfterSave() {
        ClaimData old = new ClaimData(settings.defaultRights);
        for (long c : row(0, 3, 0)) old.put(OW, c, TEAM, null, 1);
        old.put(OW, Chunks.pack(9, 9), TEAM, null, 1);
        // как файл 0.1: номеров нет
        ClaimData loaded = ClaimData.load(old.save(), settings.defaultRights);
        new ClaimService(loaded, () -> settings, new FakeTeams(), () -> 1L, new ClaimService.Hooks() {});
        assertEquals(2, loaded.land(TEAM).plots.size());
        int a = loaded.get(OW, Chunks.pack(0, 0)).plot();
        ClaimData again = ClaimData.load(loaded.save(), settings.defaultRights);
        assertEquals(a, again.get(OW, Chunks.pack(3, 0)).plot());
        assertNotNull(again.land(TEAM).plots.get(a));
        new ClaimService(again, () -> settings, new FakeTeams(), () -> 1L, new ClaimService.Hooks() {});
        assertEquals(a, again.get(OW, Chunks.pack(3, 0)).plot(), "пересчёт при загрузке номера не меняет");
    }

    @Test
    void cleanName() {
        assertEquals("База", ClaimService.cleanName("  §cБаза\n "));
        assertEquals(Plots.MAX_NAME, ClaimService.cleanName("x".repeat(100)).length());
    }
}

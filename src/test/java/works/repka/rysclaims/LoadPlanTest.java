package works.repka.rysclaims;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.LongList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import works.repka.rysclaims.data.Chunks;
import works.repka.rysclaims.data.ClaimData;
import works.repka.rysclaims.data.Plots;
import works.repka.rysclaims.data.Settings;
import works.repka.rysclaims.server.LoadPlan;

/** Какие билеты прогрузки нужны: режимы «пока в сети» и «всегда», снятие лишнего после перезапуска. */
class LoadPlanTest {
    static final String OW = "minecraft:overworld";
    static final UUID A = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
    static final UUID B = UUID.fromString("00000000-0000-0000-0000-0000000000bb");

    @Test
    void onlineAndAlways() {
        Settings s = new Settings();
        ClaimData d = new ClaimData(s.defaultRights);
        d.setForce(d.put(OW, Chunks.pack(0, 0), A, null, 0), true);
        d.put(OW, Chunks.pack(1, 0), A, null, 0);
        d.setForce(d.put(OW, Chunks.pack(9, 9), B, null, 0), true);

        // никого нет в сети: ничего не грузится
        assertTrue(LoadPlan.desired(d, s, t -> false).isEmpty());
        // A в сети: только её прогруженный чанк
        var want = LoadPlan.desired(d, s, A::equals);
        assertEquals(1, want.get(OW).size());
        assertEquals(A, want.get(OW).get(Chunks.pack(0, 0)));
        // команда выбрала «всегда», но сервер разрешает только «пока в сети»
        d.landOrCreate(B).loadMode = Settings.LoadMode.ALWAYS;
        assertFalse(LoadPlan.desired(d, s, t -> false).containsKey(OW));
        s.maxLoad = Settings.LoadMode.ALWAYS;
        assertEquals(B, LoadPlan.desired(d, s, t -> false).get(OW).get(Chunks.pack(9, 9)));
    }

    @Test
    void diffRemovesStaleAndAddsMissing() {
        Settings s = new Settings();
        ClaimData d = new ClaimData(s.defaultRights);
        d.setForce(d.put(OW, Chunks.pack(0, 0), A, null, 0), true);
        d.setForce(d.put(OW, Chunks.pack(2, 0), A, null, 0), true);
        Map<String, Long2ObjectMap<UUID>> applied = new HashMap<>();
        // после перезапуска остался билет на чанк, где прогрузку выключили, и билет чужой команды
        LoadPlan.apply(applied, new LoadPlan.Change(OW, Chunks.pack(0, 0), A, true));
        LoadPlan.apply(applied, new LoadPlan.Change(OW, Chunks.pack(5, 5), A, true));
        LoadPlan.apply(applied, new LoadPlan.Change(OW, Chunks.pack(2, 0), B, true));
        List<LoadPlan.Change> changes = LoadPlan.diff(applied, LoadPlan.desired(d, s, A::equals));
        assertEquals(3, changes.size()); // снять (5,5) и (2,0) у B, поставить (2,0) у A
        assertFalse(changes.get(0).add()); // сначала снятия
        assertFalse(changes.get(1).add());
        for (LoadPlan.Change c : changes) LoadPlan.apply(applied, c);
        assertEquals(Set.of(Chunks.pack(0, 0), Chunks.pack(2, 0)), applied.get(OW).keySet());
        assertEquals(A, applied.get(OW).get(Chunks.pack(2, 0)));
        // повтор ничего не меняет
        assertTrue(LoadPlan.diff(applied, LoadPlan.desired(d, s, A::equals)).isEmpty());
        // все вышли: всё снимается
        for (LoadPlan.Change c : LoadPlan.diff(applied, LoadPlan.desired(d, s, t -> false))) LoadPlan.apply(applied, c);
        assertTrue(applied.isEmpty());
    }

    @Test
    void plotsBySideOnly() {
        ClaimData d = new ClaimData(Settings.defaultRights());
        for (long c : Chunks.square(0, 0, 1)) d.put(OW, c, A, null, 0);
        d.put(OW, Chunks.pack(2, 2), A, null, 0); // касается только углом
        d.put(OW, Chunks.pack(10, 0), A, null, 0);
        d.put(OW, Chunks.pack(11, 0), B, null, 0);
        List<LongList> groups = Plots.groups(d, A, OW);
        assertEquals(3, groups.size());
        assertEquals(9, groups.get(0).size());
        assertEquals(Chunks.pack(0, 0), Plots.center(groups.get(0)));
    }
}

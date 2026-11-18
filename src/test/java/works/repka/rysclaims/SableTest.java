package works.repka.rysclaims;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;
import works.repka.rysclaims.compat.sable.SableLand;
import works.repka.rysclaims.data.Chunks;
import works.repka.rysclaims.data.ClaimData;
import works.repka.rysclaims.data.Settings;
import works.repka.rysclaims.server.ClaimService;

/** Sable без игры: сетка участков, занять чанк участка нельзя, дом подуровня в файле, без Sable позиция та же. */
class SableTest {
    @Test
    void plotGrid() {
        assertTrue(Chunks.sablePlot(1_280_000, 1_280_000), "первый чанк сетки");
        assertTrue(Chunks.sablePlot(1_296_383, 1_296_383), "последний чанк сетки");
        assertFalse(Chunks.sablePlot(1_279_999, 1_280_000), "до сетки");
        assertFalse(Chunks.sablePlot(1_296_384, 1_280_000), "после сетки");
        assertFalse(Chunks.sablePlot(0, 0));
        assertFalse(Chunks.sablePlot(-1_280_000, 1_280_000));
        assertTrue(Chunks.sablePlot(Chunks.pack(1_290_000, 1_281_234)));
    }

    @Test
    void plotChunksAreNotClaimable() {
        Settings settings = new Settings();
        UUID team = UUID.randomUUID();
        ClaimService s = new ClaimService(new ClaimData(settings.defaultRights), () -> settings, new FakeTeams(), () -> 0L, new ClaimService.Hooks() {});
        ClaimService.Result r = s.claim(null, team, "minecraft:overworld", LongArrayList.of(Chunks.pack(1_285_000, 1_285_000), Chunks.pack(3, 3)), true);
        assertEquals(1, r.done);
        assertEquals(1, r.skipped.get(ClaimService.Skip.DIMENSION));
    }

    @Test
    void homesAreSaved() {
        Settings settings = new Settings();
        ClaimData data = new ClaimData(settings.defaultRights);
        UUID sub = UUID.randomUUID(), team = UUID.randomUUID();
        data.home(sub, team);
        ClaimData back = ClaimData.load(data.save(), settings.defaultRights);
        assertEquals(team, back.home(sub));
        back.home(sub, null);
        assertNull(ClaimData.load(back.save(), settings.defaultRights).home(sub));
    }

    @Test
    void withoutSableThePositionIsTheSame() {
        BlockPos inPlot = new BlockPos(1_285_000 * 16, 64, 1_285_000 * 16);
        assertSame(inPlot, SableLand.toWorld(null, inPlot));
        BlockPos outside = new BlockPos(100, 64, 100);
        assertSame(outside, SableLand.toWorld(null, outside));
    }
}

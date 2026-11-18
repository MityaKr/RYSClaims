package works.repka.rysclaims;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import works.repka.rysclaims.data.Chunks;
import works.repka.rysclaims.data.ClaimData;
import works.repka.rysclaims.data.Rights;
import works.repka.rysclaims.net.RegionBytes;
import works.repka.rysclaims.server.TeamLookup;

/** З4 без игры: регион для сети, столбцы окна «Права», поиск команды в /claims admin for, слушатель правок земли. */
class SyncTest {
    private static final UUID A = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID B = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final String OW = "minecraft:overworld";

    @Test
    void emptyRegionIsOneByte() {
        byte[] codes = new byte[RegionBytes.SIZE];
        byte[] enc = RegionBytes.encode(codes);
        assertEquals(1, enc.length);
        assertArrayEquals(codes, RegionBytes.decode(enc));
    }

    @Test
    void regionRoundTripAndSizeCap() {
        ClaimData d = new ClaimData(new Rights());
        for (int x = 0; x < 10; x++) for (int z = 0; z < 4; z++) d.put(OW, Chunks.pack(32 + x, -32 + z), x < 5 ? A : B, null, 0);
        d.setForce(d.get(OW, Chunks.pack(33, -31)), true);
        d.setRelease(d.get(OW, Chunks.pack(40, -30)), 99);
        RegionBytes.Built b = RegionBytes.build(c -> d.get(OW, c), 1, -1);
        assertEquals(List.of(A, B), b.palette());
        byte[] enc = RegionBytes.encode(b.codes());
        assertTrue(enc.length < 100, "обычный регион занимает десятки байт: " + enc.length);
        byte[] back = RegionBytes.decode(enc);
        assertArrayEquals(b.codes(), back);
        int i = RegionBytes.index(33, -31);
        assertEquals(RegionBytes.FORCE, back[i] & RegionBytes.FORCE);
        assertEquals(1, back[i] & RegionBytes.TEAM_MASK);
        assertEquals(RegionBytes.RELEASING, back[RegionBytes.index(40, -30)] & RegionBytes.RELEASING);
        assertEquals(0, back[RegionBytes.index(60, -1)]);

        // худший случай (шахматка) уходит как есть: не больше 1025 байт
        byte[] chess = new byte[RegionBytes.SIZE];
        Random r = new Random(1);
        for (int k = 0; k < chess.length; k++) chess[k] = (byte) (r.nextInt(3));
        byte[] worst = RegionBytes.encode(chess);
        assertTrue(worst.length <= RegionBytes.SIZE + 1);
        assertArrayEquals(chess, RegionBytes.decode(worst));
    }

    @Test
    void regionOfNegativeChunks() {
        assertEquals(Chunks.pack(-1, -1), RegionBytes.regionOf(Chunks.pack(-1, -32)));
        assertEquals(Chunks.pack(-2, 0), RegionBytes.regionOf(Chunks.pack(-33, 31)));
    }

    @Test
    void windowColumnsStayMonotonic() {
        Rights r = Rights.of(Rights.Preset.TEAM_AND_ALLIES);
        assertTrue(r.shown(Rights.Action.BUILD, Rights.Column.ALLIES));
        r.toggle(Rights.Action.BUILD, Rights.Column.TEAM, false);
        assertFalse(r.shown(Rights.Action.BUILD, Rights.Column.TEAM));
        assertFalse(r.shown(Rights.Action.BUILD, Rights.Column.ALLIES), "снятая команда снимает союзников");
        assertTrue(r.allows(Rights.Action.BUILD, Rights.Relation.TEAM, Rights.LEADER), "лидеру можно всё");
        r.toggle(Rights.Action.OPEN, Rights.Column.ALL, true);
        assertTrue(r.shown(Rights.Action.OPEN, Rights.Column.ALL));
        assertTrue(r.shown(Rights.Action.OPEN, Rights.Column.ALLIES));
        r.toggle(Rights.Action.OPEN, Rights.Column.ALLIES, false);
        assertFalse(r.shown(Rights.Action.OPEN, Rights.Column.ALL), "снятые союзники снимают «всех»");
        assertTrue(r.shown(Rights.Action.OPEN, Rights.Column.TEAM));
        assertNull(r.preset());

        Rights back = Rights.fromBits(r.bits());
        assertEquals(r, back);
        assertEquals(Rights.Preset.OPEN, Rights.fromBits(Rights.of(Rights.Preset.OPEN).bits()).preset());
    }

    @Test
    void exactTeamNameBeatsPlayerNick() {
        Map<UUID, String> names = new LinkedHashMap<>();
        names.put(A, "VENIK");
        UUID venikPlayerTeam = B;
        assertEquals(A, TeamLookup.resolve("VENIK", names, n -> n.equalsIgnoreCase("venik") ? venikPlayerTeam : null));
        assertEquals(B, TeamLookup.resolve("Venik", names, n -> n.equalsIgnoreCase("venik") ? venikPlayerTeam : null));
        assertEquals(A, TeamLookup.resolve("venik", names, n -> null), "без игрока имя команды без учёта регистра");
        assertEquals(A, TeamLookup.resolve(A.toString(), names, n -> null));
        assertNull(TeamLookup.resolve("nobody", names, n -> null));
    }

    @Test
    void changesReportEveryEdit() {
        ClaimData d = new ClaimData(new Rights());
        List<Long> seen = new ArrayList<>();
        int[] all = {0};
        d.listen(new ClaimData.Changes() {
            @Override
            public void chunk(String dimension, long chunk) {
                seen.add(chunk);
            }

            @Override
            public void all() {
                all[0]++;
            }
        });
        long c = Chunks.pack(3, 4);
        var claim = d.put(OW, c, A, null, 0);
        d.setForce(claim, true);
        d.setForce(claim, true);
        d.setRelease(claim, 10);
        d.setRelease(claim, 20);
        d.reassign(OW, c, B);
        d.remove(OW, c);
        assertEquals(List.of(c, c, c, c, c), seen, "занят, прогрузка, отсчёт, передан, освобождён; повтор без перемены молчит");
        d.clear();
        assertEquals(1, all[0]);
    }

    /** З10: столбцы по ролям монотонны; снятая роль снимает «Союзников» и «Всех», но остальные роли остаются, как их видно. */
    @Test
    void roleColumns() {
        Rights r = Rights.of(Rights.Preset.TEAM_AND_ALLIES);
        assertTrue(r.shownRole(Rights.Action.BUILD, Rights.GUEST), "гость открыт через союзников");
        r.toggleRole(Rights.Action.BUILD, Rights.OFFICER, false);
        assertFalse(r.shownRole(Rights.Action.BUILD, Rights.OFFICER));
        assertFalse(r.shownRole(Rights.Action.BUILD, Rights.ALLIES), "снятая роль снимает союзников");
        assertTrue(r.shownRole(Rights.Action.BUILD, Rights.GUEST), "гость остался, каким его было видно");
        assertTrue(r.shownRole(Rights.Action.BUILD, Rights.MEMBER));
        r.toggleRole(Rights.Action.BUILD, Rights.ALL, true);
        for (String c : Rights.COLUMNS) assertTrue(r.shownRole(Rights.Action.BUILD, c), c);
        r.toggleRole(Rights.Action.BUILD, Rights.LEADER, false);
        assertTrue(r.shownRole(Rights.Action.BUILD, Rights.LEADER), "лидера не снять");
        Rights back = Rights.fromBits(r.bits());
        assertEquals(r, back);
    }
}

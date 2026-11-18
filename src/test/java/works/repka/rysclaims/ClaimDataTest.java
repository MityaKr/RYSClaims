package works.repka.rysclaims;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import org.junit.jupiter.api.Test;
import works.repka.rysclaims.data.Chunks;
import works.repka.rysclaims.data.Claim;
import works.repka.rysclaims.data.ClaimData;
import works.repka.rysclaims.data.Rights;
import works.repka.rysclaims.data.Settings;
import works.repka.rysclaims.data.TeamLand;

/** Файл {@code rysclaims.dat}: запись и чтение NBT с полем {@code v}, счётчики после чтения, пустые записи не пишутся. */
class ClaimDataTest {
    static final UUID A = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
    static final UUID B = UUID.fromString("00000000-0000-0000-0000-0000000000bb");
    static final UUID P = UUID.fromString("00000000-0000-0000-0000-000000000001");
    final Rights defaults = Settings.defaultRights();

    static CompoundTag roundTrip(CompoundTag tag) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        NbtIo.write(tag, new DataOutputStream(bytes));
        return NbtIo.read(new DataInputStream(new ByteArrayInputStream(bytes.toByteArray())), NbtAccounter.unlimitedHeap());
    }

    @Test
    void saveAndLoad() throws IOException {
        ClaimData d = new ClaimData(defaults);
        Claim c1 = d.put("minecraft:overworld", Chunks.pack(1, -2), A, P, 111L);
        d.put("minecraft:overworld", Chunks.pack(-30000, 30000), A, null, 222L);
        d.put("minecraft:the_nether", Chunks.pack(5, 5), B, null, 333L);
        d.setForce(c1, true);
        d.setRelease(d.get("minecraft:the_nether", Chunks.pack(5, 5)), 999L);
        TeamLand land = d.landOrCreate(A);
        land.bonusClaims = 7;
        land.bonusForce = -3;
        land.loadMode = Settings.LoadMode.ALWAYS;
        land.visibility = TeamLand.Visibility.ALLIES;
        land.rights.apply(Rights.Preset.OPEN);
        land.flag(TeamLand.Flag.PVP, false);
        land.trusted.add(P);
        land.machineNames.add("[IF]");
        land.legacyName = "Barsuki";
        land.legacySource = "ftb";
        d.bonusOrCreate(P).force = 100;
        d.edited();

        CompoundTag tag = roundTrip(d.save());
        assertEquals(ClaimData.VERSION, tag.getInt("v"));
        ClaimData back = ClaimData.load(tag, defaults);

        assertEquals(3, back.total());
        assertEquals(2, back.claimed(A));
        assertEquals(1, back.forced(A));
        assertEquals(1, back.claimed(B));
        Claim c = back.get("minecraft:overworld", Chunks.pack(1, -2));
        assertEquals(A, c.team());
        assertEquals(P, c.by());
        assertEquals(111L, c.at());
        assertTrue(c.force());
        assertEquals(A, back.owner("minecraft:overworld", Chunks.pack(-30000, 30000)));
        assertEquals(999L, back.get("minecraft:the_nether", Chunks.pack(5, 5)).releaseAt());
        TeamLand l = back.land(A);
        assertEquals(7, l.bonusClaims);
        assertEquals(-3, l.bonusForce);
        assertEquals(Settings.LoadMode.ALWAYS, l.loadMode);
        assertEquals(TeamLand.Visibility.ALLIES, l.visibility);
        assertEquals(Rights.Preset.OPEN, l.rights.preset());
        assertFalse(l.flag(TeamLand.Flag.PVP));
        assertTrue(l.flag(TeamLand.Flag.MACHINES_INSIDE));
        assertTrue(l.trusted.contains(P));
        assertEquals("[IF]", l.machineNames.get(0));
        assertEquals("Barsuki", l.legacyName);
        assertEquals(100, back.bonus(P).force);
        assertEquals(1, back.edits());
    }

    @Test
    void newerVersionIsRejected() {
        CompoundTag tag = new ClaimData(defaults).save();
        tag.putInt("v", ClaimData.VERSION + 1);
        assertThrows(IllegalStateException.class, () -> ClaimData.load(tag, defaults));
    }

    @Test
    void emptyTeamsAreNotWritten() {
        ClaimData d = new ClaimData(defaults);
        d.landOrCreate(A);
        d.bonusOrCreate(P);
        CompoundTag tag = d.save();
        assertTrue(tag.getCompound("teams").isEmpty());
        assertTrue(tag.getCompound("players").isEmpty());
    }

    @Test
    void countersFollowEdits() {
        ClaimData d = new ClaimData(defaults);
        Claim c = d.put("x:y", 1L, A, null, 0);
        assertNull(d.put("x:y", 1L, B, null, 0)); // чужое не перезаписывается
        d.setForce(c, true);
        d.reassign("x:y", 1L, B);
        assertEquals(0, d.claimed(A));
        assertEquals(0, d.forced(A));
        assertEquals(1, d.forced(B));
        d.remove("x:y", 1L);
        assertEquals(0, d.claimed(B));
        assertEquals(0, d.forced(B));
        assertTrue(d.isEmpty());
        assertFalse(d.anyIn("x:y"));
    }

    @Test
    void rightsAreMonotonic() {
        Rights r = Rights.of(Rights.Preset.TEAM);
        assertTrue(r.allows(Rights.Action.BUILD, Rights.Relation.TEAM, "member"));
        assertFalse(r.allows(Rights.Action.BUILD, Rights.Relation.TEAM, "guest"));
        assertFalse(r.allows(Rights.Action.BUILD, Rights.Relation.ALLY, null));
        r.set(Rights.Action.BUILD, Rights.ALL, true);
        assertTrue(r.allows(Rights.Action.BUILD, Rights.Relation.ALLY, null)); // «все» включает союзников
        assertTrue(r.allows(Rights.Action.BUILD, Rights.Relation.TEAM, "guest"));
        assertEquals(null, r.preset());
        r.set(Rights.Action.BUILD, "team", false);
        assertTrue(r.has(Rights.Action.BUILD, Rights.LEADER)); // лидера не снять
        assertTrue(r.allows(Rights.Action.BUILD, Rights.Relation.TEAM, "leader"));
        // права новой команды по умолчанию (строить команда, открывать и двери с союзом) ни с одним набором не совпадают
        assertNull(Settings.defaultRights().preset());
        assertEquals(Rights.Preset.TEAM_AND_ALLIES, Rights.of(Rights.Preset.TEAM_AND_ALLIES).copy().preset());
    }
}

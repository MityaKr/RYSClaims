package works.repka.rysclaims;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import works.repka.rysclaims.data.Limits;
import works.repka.rysclaims.data.PlayerBonus;
import works.repka.rysclaims.data.Rights;
import works.repka.rysclaims.data.Settings;
import works.repka.rysclaims.data.TeamLand;

/** Лимиты во всех режимах личных добавок, добавка команде, за участника, потолки. */
class LimitsTest {
    static final UUID TEAM = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
    static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    static final UUID OFFICER = UUID.fromString("00000000-0000-0000-0000-000000000002");
    static final UUID THIRD = UUID.fromString("00000000-0000-0000-0000-000000000003");

    final Map<UUID, PlayerBonus> bonus = new HashMap<>();

    Limits of(Settings s, TeamLand land, List<UUID> members, UUID leader) {
        return Limits.of(s, land, members, leader, bonus::get);
    }

    @Test
    void largestIsRepysVenik() {
        // RepYS 18.11: 25 общий, у владельца личная добавка 100, у второго нет; режим «наибольшая» даёт 125, как в FTB
        Settings s = new Settings();
        bonus.put(OWNER, new PlayerBonus(0, 100));
        Limits l = of(s, null, List.of(OWNER, OFFICER), OWNER);
        assertEquals(500, l.claims);
        assertEquals(125, l.force);
    }

    @Test
    void allModes() {
        Settings s = new Settings();
        bonus.put(OWNER, new PlayerBonus(10, 100));
        bonus.put(OFFICER, new PlayerBonus(30, 0));
        List<UUID> members = List.of(OWNER, OFFICER, THIRD);

        s.bonusMode = Settings.BonusMode.LARGEST;
        assertEquals(530, of(s, null, members, OWNER).claims);
        assertEquals(125, of(s, null, members, OWNER).force);

        s.bonusMode = Settings.BonusMode.SUM;
        assertEquals(540, of(s, null, members, OWNER).claims);
        assertEquals(125, of(s, null, members, OWNER).force);

        s.bonusMode = Settings.BonusMode.LEADER;
        assertEquals(510, of(s, null, members, OWNER).claims);
        assertEquals(25, of(s, null, members, OFFICER).force);

        s.bonusMode = Settings.BonusMode.AVERAGE;
        assertEquals(513, of(s, null, members, OWNER).claims); // (10+30+0)/3 = 13
        assertEquals(58, of(s, null, members, OWNER).force); // 100/3 = 33
    }

    @Test
    void personalBonusLeavesWithPlayer() {
        Settings s = new Settings();
        bonus.put(OWNER, new PlayerBonus(0, 100));
        assertEquals(125, of(s, null, List.of(OWNER, OFFICER), OWNER).force);
        // владелец ушёл: его добавка ушла с ним, у команды общий лимит
        assertEquals(25, of(s, null, List.of(OFFICER), OFFICER).force);
        // и пришла в его личную землю
        assertEquals(125, of(s, null, List.of(OWNER), OWNER).force);
    }

    @Test
    void teamBonusPerMemberAndCaps() {
        Settings s = new Settings();
        s.claimsPerMember = 50;
        s.forcePerMember = 5;
        TeamLand land = new TeamLand(TEAM, new Rights());
        land.bonusClaims = 20;
        land.bonusForce = -100;
        Limits l = of(s, land, List.of(OWNER, OFFICER, THIRD), OWNER);
        assertEquals(500 + 100 + 20, l.claims);
        assertEquals(0, l.force); // 25 + 10 - 100 не уходит ниже нуля
        s.hardClaims = 600;
        s.hardForce = 0;
        assertEquals(600, of(s, land, List.of(OWNER, OFFICER, THIRD), OWNER).claims);
    }

    @Test
    void parseFtbModes() {
        assertEquals(Settings.BonusMode.LEADER, Settings.BonusMode.parse("owner", null));
        assertEquals(Settings.BonusMode.LARGEST, Settings.BonusMode.parse("largest", null));
        assertEquals(Settings.AllyMode.ALL, Settings.AllyMode.parse("forced_all", null));
        assertEquals(Settings.PvpMode.TEAM, Settings.PvpMode.parse("per_team", null));
    }
}

package works.repka.rysclaims.data;

import java.util.Collection;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/**
 * Лимиты команды: общий сервера, добавка за участника, сложенные по режиму личные добавки, добавка команде, потолок.
 *
 * <p>Отличие от FTB Chunks: в FTB режим {@code sum} складывает полные лимиты участников (500 на каждого), у нас режим
 * складывает только личные добавки. Режимы {@code largest}, {@code owner}, {@code average} дают те же числа, что FTB.
 * Перенос сверяет итог с числом из файла команды FTB и дописывает разницу добавкой команде.
 */
public final class Limits {
    public final int claims;
    public final int force;

    public Limits(int claims, int force) {
        this.claims = claims;
        this.force = force;
    }

    /**
     * @param members участники команды (у соло только сам игрок)
     * @param leader лидер команды или null
     */
    public static Limits of(Settings s, @Nullable TeamLand land, Collection<UUID> members, @Nullable UUID leader,
                            java.util.function.Function<UUID, PlayerBonus> bonus) {
        int extra = Math.max(0, members.size() - 1);
        int claims = s.claimsPerTeam + s.claimsPerMember * extra;
        int force = s.forcePerTeam + s.forcePerMember * extra;
        claims += combine(s.bonusMode, members, leader, bonus, true);
        force += combine(s.bonusMode, members, leader, bonus, false);
        if (land != null) {
            claims += land.bonusClaims;
            force += land.bonusForce;
        }
        if (s.hardClaims > 0) claims = Math.min(claims, s.hardClaims);
        if (s.hardForce > 0) force = Math.min(force, s.hardForce);
        return new Limits(Math.max(0, claims), Math.max(0, force));
    }

    static int combine(Settings.BonusMode mode, Collection<UUID> members, @Nullable UUID leader,
                       java.util.function.Function<UUID, PlayerBonus> bonus, boolean claims) {
        if (members.isEmpty()) return 0;
        return switch (mode) {
            case LEADER -> leader == null ? 0 : value(bonus.apply(leader), claims);
            case LARGEST -> {
                int max = Integer.MIN_VALUE;
                for (UUID m : members) max = Math.max(max, value(bonus.apply(m), claims));
                yield max;
            }
            case SUM, AVERAGE -> {
                long sum = 0;
                for (UUID m : members) sum += value(bonus.apply(m), claims);
                yield (int) (mode == Settings.BonusMode.SUM ? sum : Math.floorDiv(sum, members.size()));
            }
        };
    }

    private static int value(@Nullable PlayerBonus b, boolean claims) {
        return b == null ? 0 : claims ? b.claims : b.force;
    }

    @Override
    public String toString() {
        return claims + "/" + force;
    }
}

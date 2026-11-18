package works.repka.rysclaims.server;

import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import org.jetbrains.annotations.Nullable;

/**
 * Команда по слову из {@code /claims admin for <команда>}: UUID, затем точное имя команды, затем ник игрока (его команда),
 * затем имя команды без учёта регистра. Точное имя команды стоит раньше ника: иначе «VENIK» находил игрока Venik, а не
 * команду VENIK (ник игрока ищется без учёта регистра).
 */
public final class TeamLookup {
    private TeamLookup() {}

    /**
     * @param names имена команд: RYSTeams и команды FTB без пары
     * @param playerTeam команда игрока по нику или null, если такого игрока нет
     */
    public static @Nullable UUID resolve(String arg, Map<UUID, String> names, Function<String, @Nullable UUID> playerTeam) {
        try {
            return UUID.fromString(arg);
        } catch (IllegalArgumentException ignored) {
            // не UUID
        }
        for (Map.Entry<UUID, String> e : names.entrySet()) if (arg.equals(e.getValue())) return e.getKey();
        UUID byPlayer = playerTeam.apply(arg);
        if (byPlayer != null) return byPlayer;
        for (Map.Entry<UUID, String> e : names.entrySet()) if (arg.equalsIgnoreCase(e.getValue())) return e.getKey();
        return null;
    }
}

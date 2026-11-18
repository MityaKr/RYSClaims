package works.repka.rysclaims.server;

import java.util.Collection;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;
import works.repka.ryscore.team.TeamsApi;

/** То, что сервису нужно знать о командах. В игре это {@link TeamsApi}, в проверках подделка. */
public interface Teams {
    UUID teamOf(UUID player);

    Collection<UUID> members(UUID team);

    boolean can(UUID player, String permission);

    /** {@code leader}, {@code officer}, {@code member}, {@code guest}. */
    String roleOf(UUID player);

    Collection<UUID> allies(UUID team);

    @Nullable
    UUID leader(UUID team);

    /** Команды через контракт ядра: без RYSTeams каждый игрок соло. */
    static Teams api() {
        return new Teams() {
            @Override
            public UUID teamOf(UUID player) {
                return TeamsApi.get().teamOf(player);
            }

            @Override
            public Collection<UUID> members(UUID team) {
                return TeamsApi.get().members(team);
            }

            @Override
            public boolean can(UUID player, String permission) {
                return TeamsApi.get().can(player, permission);
            }

            @Override
            public String roleOf(UUID player) {
                return TeamsApi.get().roleOf(player);
            }

            @Override
            public Collection<UUID> allies(UUID team) {
                return TeamsApi.get().allies(team);
            }

            @Override
            public @Nullable UUID leader(UUID team) {
                return TeamsApi.get().leader(team);
            }
        };
    }
}

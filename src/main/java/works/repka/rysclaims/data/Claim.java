package works.repka.rysclaims.data;

import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/**
 * Занятый чанк: чей, кто принёс, когда, прогрузка, отсчёт освобождения. Меняется только через {@link ClaimData}: она ведёт
 * счётчики команд.
 */
public final class Claim {
    final UUID team;
    /** Кто принёс чанк в команду (поле 0.2, в 0.1 пишется, но не используется). */
    final @Nullable UUID by;
    /** Когда занят, мс от эпохи. */
    final long at;
    /** Номер участка (поле 0.2). */
    int plot;
    boolean force;
    /** Когда освободится, мс от эпохи; 0: не освобождается. */
    long releaseAt;

    Claim(UUID team, @Nullable UUID by, long at) {
        this.team = team;
        this.by = by;
        this.at = at;
    }

    public UUID team() {
        return team;
    }

    public @Nullable UUID by() {
        return by;
    }

    public long at() {
        return at;
    }

    public int plot() {
        return plot;
    }

    public boolean force() {
        return force;
    }

    public long releaseAt() {
        return releaseAt;
    }

    public boolean releasing() {
        return releaseAt > 0;
    }
}

package works.repka.rysclaims.data;

import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/**
 * Занятый чанк: чей, кто принёс, когда, прогрузка, отсчёт освобождения. Меняется только через {@link ClaimData}: она ведёт
 * счётчики команд.
 */
public final class Claim {
    /** Где лежит: измерение и упакованный чанк. По ним правка сообщает о себе рассылке. */
    final String dimension;
    final long pos;
    final UUID team;
    /** Кто принёс чанк в команду: показывается у участка, по нему ушедший забирает свои чанки. */
    final @Nullable UUID by;
    /** Когда занят, мс от эпохи. */
    final long at;
    /** Номер участка команды ({@link Plots}); 0: ещё не посчитан. */
    int plot;
    boolean force;
    /** Когда освободится, мс от эпохи; 0: не освобождается. */
    long releaseAt;

    Claim(String dimension, long pos, UUID team, @Nullable UUID by, long at) {
        this.dimension = dimension;
        this.pos = pos;
        this.team = team;
        this.by = by;
        this.at = at;
    }

    public String dimension() {
        return dimension;
    }

    public long pos() {
        return pos;
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

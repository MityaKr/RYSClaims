package works.repka.rysclaims.compat.sable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.jetbrains.annotations.Nullable;
import works.repka.rysclaims.RYSClaims;
import works.repka.rysclaims.data.Chunks;
import works.repka.rysclaims.server.ClaimService;
import works.repka.rysclaims.server.ClaimsServer;
import works.repka.rysclaims.server.Loader;

/** Сборка постройки Sable: что из блоков можно забрать и чей у постройки дом. Зовётся из {@code SableAssemblyMixin}. */
public final class SableAssembly {
    private SableAssembly() {}

    /**
     * Блоки сборки без тех, что лежат на земле другой команды, чем якорь (ничья земля берётся всегда). Отдаётся новый
     * список: исходный может быть одноразовым.
     */
    public static Iterable<BlockPos> filter(ServerLevel level, BlockPos anchor, Iterable<BlockPos> blocks) {
        if (!ClaimsServer.running() || blocks == null) return blocks;
        ClaimService s = ClaimsServer.service();
        String dim = Loader.id(level.dimension());
        if (!s.data().anyIn(dim) || !s.settings().protection) return blocks;
        UUID home = owner(s, level, dim, anchor);
        List<BlockPos> kept = new ArrayList<>();
        int dropped = 0;
        for (BlockPos p : blocks) {
            UUID o = owner(s, level, dim, p);
            if (o == null || o.equals(home)) kept.add(p.immutable());
            else dropped++;
        }
        if (dropped > 0) RYSClaims.LOG.info("RYSClaims: сборка постройки Sable у {} не взяла {} блоков с чужой земли", anchor.toShortString(), dropped);
        return kept;
    }

    /** После сборки: дом подуровня это команда земли якоря; на ничьей земле команда игрока сборщика, если он известен, иначе дома нет. */
    public static void assembled(ServerLevel level, BlockPos anchor, @Nullable Object subLevel) {
        if (subLevel == null || !ClaimsServer.running()) return;
        UUID id = SableLand.id(subLevel);
        if (id == null) return;
        ClaimService s = ClaimsServer.service();
        UUID home = owner(s, level, Loader.id(level.dimension()), anchor);
        // ничья земля: дом это команда игрока, который дёрнул рычаг сборщика (иначе свою постройку не разобрать у себя)
        net.minecraft.world.entity.player.Player actor = SableDisassembly.actor();
        if (home == null && actor != null) home = s.teams().teamOf(actor.getUUID());
        s.data().home(id, home);
        ClaimsServer.markDirty();
    }

    private static @Nullable UUID owner(ClaimService s, ServerLevel level, String dim, BlockPos pos) {
        BlockPos w = SableLand.toWorld(level, pos);
        return s.data().owner(dim, Chunks.pack(w.getX() >> 4, w.getZ() >> 4));
    }
}

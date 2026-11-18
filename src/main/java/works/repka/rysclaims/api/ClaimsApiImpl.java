package works.repka.rysclaims.api;

import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;
import works.repka.ryscore.claims.ClaimsApi;
import works.repka.rysclaims.data.Chunks;
import works.repka.rysclaims.data.Claim;
import works.repka.rysclaims.data.Rights;
import works.repka.rysclaims.server.ClaimsServer;
import works.repka.rysclaims.server.Loader;

/** Реализация контракта ядра. Пока сервер не запущен, отвечает как «земли нет». */
public final class ClaimsApiImpl implements ClaimsApi {
    @Override
    public boolean present() {
        return true;
    }

    @Override
    public @Nullable UUID ownerAt(ResourceKey<Level> dimension, int chunkX, int chunkZ) {
        if (!ClaimsServer.running()) return null;
        return ClaimsServer.service().data().owner(Loader.id(dimension), Chunks.pack(chunkX, chunkZ));
    }

    @Override
    public boolean can(UUID player, ResourceKey<Level> dimension, BlockPos pos, Action action) {
        if (!ClaimsServer.running()) return true;
        return ClaimsServer.service().can(player, Loader.id(dimension), Chunks.pack(pos.getX() >> 4, pos.getZ() >> 4), map(action));
    }

    @Override
    public boolean canMachine(ResourceKey<Level> dimension, BlockPos from, @Nullable UUID profile, BlockPos target, Action action) {
        if (!ClaimsServer.running()) return true;
        return ClaimsServer.service().canMachine(Loader.id(dimension), Chunks.pack(from.getX() >> 4, from.getZ() >> 4), profile,
            Chunks.pack(target.getX() >> 4, target.getZ() >> 4), map(action));
    }

    @Override
    public boolean forceLoaded(ResourceKey<Level> dimension, int chunkX, int chunkZ) {
        if (!ClaimsServer.running()) return false;
        Claim c = ClaimsServer.service().data().get(Loader.id(dimension), Chunks.pack(chunkX, chunkZ));
        return c != null && c.force();
    }

    @Override
    public int claimed(UUID team) {
        return ClaimsServer.running() ? ClaimsServer.service().data().claimed(team) : 0;
    }

    @Override
    public int limit(UUID team) {
        return ClaimsServer.running() ? ClaimsServer.service().limits(team).claims : 0;
    }

    @Override
    public void addBonus(UUID team, int claims, int forceLoaded) {
        if (ClaimsServer.running()) ClaimsServer.service().addBonus(team, claims, forceLoaded);
    }

    @Override
    public void addPlayerBonus(UUID player, int claims, int forceLoaded) {
        if (ClaimsServer.running()) ClaimsServer.service().addPlayerBonus(player, claims, forceLoaded);
    }

    public static Rights.Action map(Action action) {
        return switch (action) {
            case BUILD -> Rights.Action.BUILD;
            case OPEN -> Rights.Action.OPEN;
            case USE -> Rights.Action.USE;
            case ENTITY -> Rights.Action.ENTITY;
        };
    }
}

package works.repka.rysclaims.data;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import works.repka.rysclaims.server.ServerConfig;

/** Файл {@code world/data/rysclaims.dat} в хранилище Верхнего мира. Сами данные и формат: {@link ClaimData}. */
public final class ClaimSavedData extends SavedData {
    public static final String NAME = "rysclaims";

    private final ClaimData data;

    private ClaimSavedData(ClaimData data) {
        this.data = data;
    }

    public static ClaimSavedData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(
            new SavedData.Factory<>(() -> new ClaimSavedData(new ClaimData(ServerConfig.snapshot().defaultRights)), ClaimSavedData::load), NAME);
    }

    private static ClaimSavedData load(CompoundTag tag, HolderLookup.Provider registries) {
        return new ClaimSavedData(ClaimData.load(tag, ServerConfig.snapshot().defaultRights));
    }

    public ClaimData data() {
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        CompoundTag out = data.save();
        for (String key : out.getAllKeys()) tag.put(key, out.get(key));
        return tag;
    }
}

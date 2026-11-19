package works.repka.rysclaims.server;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.common.world.chunk.RegisterTicketControllersEvent;
import net.neoforged.neoforge.common.world.chunk.TicketController;
import net.neoforged.neoforge.common.world.chunk.TicketHelper;
import net.neoforged.neoforge.common.world.chunk.TicketSet;
import works.repka.rysclaims.RYSClaims;
import works.repka.rysclaims.data.ClaimData;
import works.repka.rysclaims.data.ClaimSavedData;
import works.repka.rysclaims.data.Chunks;
import works.repka.rysclaims.data.Settings;

/**
 * Прогрузка своими билетами: контроллер {@code rysclaims:land}, билет «сущность» с UUID команды, {@code ticking = true}
 * (как у FTB Chunks, поэтому перенесённая прогрузка грузит мир так же). NeoForge сам хранит билеты в
 * {@code data/chunks.dat} и восстанавливает их при запуске; проверка {@link #validate} при этом снимает лишние. Дальше
 * {@link #sync} сверяет поставленное с нужным ({@link LoadPlan}).
 */
public final class Loader {
    public static final TicketController CONTROLLER =
        new TicketController(ResourceLocation.fromNamespaceAndPath(RYSClaims.MODID, "land"), Loader::validate);

    /** Поставленные билеты: измерение → чанк → команда. */
    private static final Map<String, Long2ObjectMap<UUID>> APPLIED = new HashMap<>();
    private static int removedAtStart;

    private Loader() {}

    public static void register(RegisterTicketControllersEvent e) {
        e.register(CONTROLLER);
    }

    /** Проверка при загрузке мира: билеты без включённой прогрузки или у неактивной команды снимаются. */
    private static void validate(ServerLevel level, TicketHelper helper) {
        ClaimData data = ClaimSavedData.get(level.getServer()).data();
        Settings s = ServerConfig.snapshot();
        String dim = id(level.dimension());
        Long2ObjectMap<UUID> want = LoadPlan.desired(data, s, t -> false).get(dim);
        for (Map.Entry<UUID, TicketSet> e : Map.copyOf(helper.getEntityTickets()).entrySet()) {
            UUID owner = e.getKey();
            for (long chunk : new LongArrayList(e.getValue().nonTicking())) {
                helper.removeTicket(owner, chunk, false);
                removedAtStart++;
            }
            for (long chunk : new LongArrayList(e.getValue().ticking())) {
                if (want != null && owner.equals(want.get(chunk))) {
                    LoadPlan.apply(APPLIED, new LoadPlan.Change(dim, chunk, owner, true));
                } else {
                    helper.removeTicket(owner, chunk, true);
                    removedAtStart++;
                }
            }
        }
        // блоковых билетов контроллер не ставит: если они есть, они чужие по ошибке
        for (Map.Entry<net.minecraft.core.BlockPos, TicketSet> e : Map.copyOf(helper.getBlockTickets()).entrySet()) {
            helper.removeAllTickets(e.getKey());
        }
    }

    /** Сколько лишних билетов снято при запуске; сбрасывает счётчик. */
    public static int takeRemovedAtStart() {
        int n = removedAtStart;
        removedAtStart = 0;
        return n;
    }

    /** Сверяет билеты с нужными. Возвращает число изменений. */
    public static int sync(MinecraftServer server, ClaimService service) {
        Set<UUID> online = new HashSet<>();
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (p instanceof FakePlayer) continue;
            online.add(p.getUUID());
            online.add(service.teams().teamOf(p.getUUID()));
        }
        var desired = LoadPlan.desired(service.data(), service.settings(), online::contains);
        int n = 0;
        for (LoadPlan.Change c : LoadPlan.diff(APPLIED, desired)) {
            ServerLevel level = server.getLevel(key(c.dimension()));
            if (level == null) {
                // измерения больше нет: билет снимать негде, нужного не поставить
                if (!c.add()) LoadPlan.apply(APPLIED, c);
                continue;
            }
            CONTROLLER.forceChunk(level, c.team(), Chunks.x(c.chunk()), Chunks.z(c.chunk()), c.add(), true);
            LoadPlan.apply(APPLIED, c);
            n++;
        }
        return n;
    }

    /** Все поставленные билеты, только для чтения: {@code /claims admin loaded}. */
    public static Map<String, Long2ObjectMap<UUID>> applied() {
        return java.util.Collections.unmodifiableMap(APPLIED);
    }

    public static void clear() {
        APPLIED.clear();
        removedAtStart = 0;
    }

    /** Строка измерения по ключу: ключи игры одни на измерение, строка строится один раз (разбор логики 19.11, раздел 5, пункт 5). */
    private static final Map<ResourceKey<Level>, String> IDS = new java.util.concurrent.ConcurrentHashMap<>();

    public static String id(ResourceKey<Level> dimension) {
        return IDS.computeIfAbsent(dimension, k -> k.location().toString());
    }

    public static ResourceKey<Level> key(String dimension) {
        return ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(dimension));
    }
}

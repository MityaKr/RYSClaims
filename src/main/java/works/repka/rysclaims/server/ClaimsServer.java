package works.repka.rysclaims.server;

import it.unimi.dsi.fastutil.longs.LongList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;
import works.repka.ryscore.claims.ClaimsApi;
import works.repka.rysclaims.RYSClaims;
import works.repka.rysclaims.data.ClaimSavedData;
import works.repka.rysclaims.data.TeamLand;

/** Земля на запущенном сервере: файл, сервис, прогрузка, тик. Один на сервер, живёт от запуска до остановки. */
public final class ClaimsServer {
    private static @Nullable MinecraftServer server;
    private static @Nullable ClaimSavedData saved;
    private static @Nullable ClaimService service;
    private static boolean loaderDirty;
    private static int ticks;

    private ClaimsServer() {}

    /** {@code ServerStartingEvent}: мир загружен, игроков ещё нет. Переезд с FTB (ядро, ServerStarted) идёт после. */
    public static void start(MinecraftServer s) {
        server = s;
        ServerConfig.invalidate();
        saved = ClaimSavedData.get(s);
        saved.data().defaults(ServerConfig.snapshot().defaultRights);
        service = new ClaimService(saved.data(), ServerConfig::snapshot, Teams.api(), System::currentTimeMillis, new Hooks());
        int removed = Loader.takeRemovedAtStart();
        int changed = Loader.sync(s, service);
        RYSClaims.LOG.info("RYSClaims: чанков {}, команд {}, лишних билетов снято {}, билетов поставлено {}",
            saved.data().total(), saved.data().lands().size(), removed, changed);
    }

    public static void stop() {
        server = null;
        saved = null;
        service = null;
        Loader.clear();
    }

    public static boolean running() {
        return service != null;
    }

    public static ClaimService service() {
        ClaimService s = service;
        if (s == null) throw new IllegalStateException("RYSClaims: сервер не запущен");
        return s;
    }

    public static @Nullable MinecraftServer server() {
        return server;
    }

    public static void tick() {
        ClaimService s = service;
        MinecraftServer srv = server;
        if (s == null || srv == null) return;
        ticks++;
        if (ticks % 20 == 0) s.tick();
        if (loaderDirty || ticks % 200 == 0) {
            loaderDirty = false;
            Loader.sync(srv, s);
        }
    }

    /** Прогрузка пересчитается на следующем тике (игрок вышел: к тому времени его уже нет в списке). */
    public static void loaderDirty() {
        loaderDirty = true;
    }

    public static void login(ServerPlayer player) {
        ClaimService s = service;
        if (s == null) return;
        TeamLand land = s.data().land(s.teams().teamOf(player.getUUID()));
        if (land != null) {
            land.lastSeen = System.currentTimeMillis();
            markDirty();
        }
        loaderDirty = true;
    }

    /** Пишет файл земли сейчас, а не при очередном сохранении мира: переезд делает необратимое только после записи. */
    public static void saveNow() {
        MinecraftServer srv = server;
        ClaimSavedData d = saved;
        if (srv == null || d == null) return;
        d.setDirty();
        srv.overworld().getDataStorage().save();
    }

    public static void markDirty() {
        ClaimSavedData d = saved;
        if (d != null) d.setDirty();
    }

    private static final class Hooks implements ClaimService.Hooks {
        @Override
        public void claimed(UUID team, String dimension, LongList chunks) {
            for (ClaimsApi.Listener l : ClaimsApi.listeners()) l.onClaimed(team, Loader.key(dimension), new LongOpenHashSet(chunks));
        }

        @Override
        public void released(UUID team, String dimension, LongList chunks) {
            for (ClaimsApi.Listener l : ClaimsApi.listeners()) l.onReleased(team, Loader.key(dimension), new LongOpenHashSet(chunks));
        }

        @Override
        public void forceChanged(UUID team) {
            loaderDirty = true;
        }

        @Override
        public void dirty() {
            markDirty();
        }
    }
}

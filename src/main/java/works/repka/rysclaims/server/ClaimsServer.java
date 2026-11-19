package works.repka.rysclaims.server;

import it.unimi.dsi.fastutil.longs.LongList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;
import org.jetbrains.annotations.Nullable;
import works.repka.ryscore.claims.ClaimsApi;
import works.repka.rysclaims.RYSClaims;
import works.repka.rysclaims.data.ClaimData;
import works.repka.rysclaims.data.ClaimSavedData;
import works.repka.rysclaims.data.TeamLand;

/** Земля на запущенном сервере: файл, сервис, прогрузка, тик. Один на сервер, живёт от запуска до остановки. */
public final class ClaimsServer {
    private static @Nullable MinecraftServer server;
    private static @Nullable ClaimSavedData saved;
    private static @Nullable ClaimService service;
    private static @Nullable DenyLog log;
    private static @Nullable ExecutorService logWriter;
    private static boolean loaderDirty;
    private static int ticks;
    /** Журнал уходит в файл раз в минуту, чистится раз в сутки. */
    private static final int LOG_FLUSH_TICKS = 1200, LOG_PRUNE_TICKS = 20 * 60 * 60 * 24;

    private ClaimsServer() {}

    /** {@code ServerStartingEvent}: мир загружен, игроков ещё нет. Переезд с FTB (ядро, ServerStarted) идёт после. */
    public static void start(MinecraftServer s) {
        server = s;
        ServerConfig.invalidate();
        saved = ClaimSavedData.get(s);
        saved.data().defaults(ServerConfig.snapshot().defaultRights);
        service = new ClaimService(saved.data(), ServerConfig::snapshot, Teams.api(), System::currentTimeMillis, new Hooks());
        ClaimSync.attach(saved.data());
        logWriter = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "RYSClaims log");
            t.setDaemon(true);
            return t;
        });
        log = new DenyLog(s.getWorldPath(LevelResource.ROOT).resolve("rysclaims").resolve("log"), System::currentTimeMillis, logWriter);
        log.prune(ServerConfig.snapshot().logDays);
        int removed = Loader.takeRemovedAtStart();
        int changed = Loader.sync(s, service);
        RYSClaims.LOG.info("RYSClaims: чанков {}, команд {}, лишних билетов снято {}, билетов поставлено {}",
            saved.data().total(), saved.data().lands().size(), removed, changed);
    }

    public static void stop() {
        DenyLog l = log;
        ExecutorService w = logWriter;
        log = null;
        logWriter = null;
        if (l != null) l.flush();
        if (w != null) {
            w.shutdown();
            try {
                if (!w.awaitTermination(5, TimeUnit.SECONDS)) RYSClaims.LOG.warn("RYSClaims: журнал отказов не дописан за 5 секунд");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        ClaimData d = saved == null ? null : saved.data();
        if (d != null) d.listen(null);
        ClaimSync.clear();
        Alerts.clear();
        Feed.clear();
        Membership.clear();
        PlotDeals.clear();
        server = null;
        saved = null;
        service = null;
        Loader.clear();
    }

    /** Журнал отказов; null, пока сервер не запущен. */
    public static @Nullable DenyLog log() {
        return log;
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
        if (settingsPending) {
            settingsPending = false;
            settingsChanged();
        }
        if (ticks % 20 == 0) {
            s.tick();
            dropBypass(srv, s);
        }
        DenyLog l = log;
        if (l != null) {
            if (ticks % LOG_FLUSH_TICKS == 0) {
                l.flush();
                Alerts.flush(s);
            }
            if (ticks % LOG_PRUNE_TICKS == 0) l.prune(s.settings().logDays);
        }
        if (ticks % 200 == 0) Feed.flush(srv);
        if (ticks % 1200 == 600) {
            s.overLimitTick();
            // кто в сети, у того простоя нет
            java.util.Set<java.util.UUID> online = new java.util.HashSet<>();
            for (ServerPlayer p : srv.getPlayerList().getPlayers()) online.add(s.teams().teamOf(p.getUUID()));
            for (java.util.UUID t : online) s.seen(t);
            s.idleTick();
            s.leaseTick();
        }
        if (loaderDirty || ticks % 200 == 0) {
            loaderDirty = false;
            Loader.sync(srv, s);
        }
        ClaimSync.tick(srv, s);
        Membership.flush();
    }

    /**
     * Режим оператора держится, пока игрок в сети и у него уровень 2 (перепроверка 18.11, пункт 9): раньше снятый op до
     * перезапуска строил и открывал везде. Проверка раз в секунду; вышедший теряет режим сразу ({@code ClaimSync.logout}).
     */
    private static void dropBypass(MinecraftServer srv, ClaimService s) {
        for (java.util.UUID id : s.bypassers()) {
            ServerPlayer p = srv.getPlayerList().getPlayer(id);
            if (p != null && p.hasPermissions(2)) continue;
            s.clearBypass(id);
            if (p != null) p.sendSystemMessage(Msg.t("bypass.off", "Operator mode off"));
        }
    }

    /** Прогрузка пересчитается на следующем тике (игрок вышел: к тому времени его уже нет в списке). */
    public static void loaderDirty() {
        loaderDirty = true;
    }

    public static void login(ServerPlayer player) {
        ClaimService s = service;
        if (s == null) return;
        s.seen(s.teams().teamOf(player.getUUID()));
        loaderDirty = true;
        ClaimSync.login(player);
        Membership.login(player);
    }

    /** Пишет файл земли сейчас, а не при очередном сохранении мира: переезд делает необратимое только после записи. */
    public static void saveNow() {
        MinecraftServer srv = server;
        ClaimSavedData d = saved;
        if (srv == null || d == null) return;
        d.setDirty();
        srv.overworld().getDataStorage().save();
    }

    private static volatile boolean settingsPending;

    /** Файл настроек перечитан (поток слежения за файлами): {@link #settingsChanged} на ближайшем тике. */
    public static void settingsChangedLater() {
        settingsPending = true;
    }

    /** Оператор поменял настройку в окне: права новых команд, версия прав, регионы заново (видимость земли). */
    public static void settingsChanged() {
        ClaimSavedData d = saved;
        if (d != null) d.data().defaults(ServerConfig.snapshot().defaultRights);
        ClaimSync.accessChanged();
        loaderDirty = true;
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
            ClaimSync.landChanged(team);
        }

        @Override
        public void landChanged(UUID team) {
            ClaimSync.landChanged(team);
        }

        @Override
        public void landEdited(UUID actor, UUID team, String op, int count) {
            Feed.edited(actor, team, op, count);
        }

        @Override
        public void plotMoved(UUID from, UUID to, String name, int count, int days) {
            if (service != null) PlotDeals.feed(service, from, to, name, count, days);
            ClaimSync.landChanged(from);
            ClaimSync.landChanged(to);
            ClaimSync.accessChanged();
            ClaimsServer.loaderDirty();
        }

        @Override
        public void idle(UUID team, String kind, int count, int days) {
            switch (kind) {
                case "warn" -> Feed.post(team, Msg.t("feed.idle.warn", "Nobody from the team has logged in for a long time: in %s days the land is released (%s chunks). Any login keeps it", days, count), true);
                case "unforce" -> Feed.post(team, Msg.t("feed.idle.unforce", "Nobody logged in for %s days: force loading is off (%s chunks)", days, count), true);
                default -> Feed.post(team, Msg.t("feed.idle.unclaim", "Nobody logged in for %s days: the land is being released (%s chunks)", days, count), true);
            }
            ClaimsServer.loaderDirty();
        }

        @Override
        public void overLimitReleased(UUID team, int count) {
            if (count > 0) Feed.post(team, Msg.t("feed.over_limit", "Land over the limit after the merge: %s chunks are being released", count), true);
        }

        @Override
        public void plotsChanged(UUID team, java.util.List<works.repka.rysclaims.data.Plots.Event> events) {
            ClaimSync.landChanged(team);
            Feed.plots(team, events);
        }

        @Override
        public void dirty() {
            markDirty();
        }

        @Override
        public void accessChanged() {
            ClaimSync.accessChanged();
        }

        @Override
        public void visibilityChanged(UUID team) {
            ClaimSync.accessChanged();
            ClaimSync.resendAll();
        }
    }
}

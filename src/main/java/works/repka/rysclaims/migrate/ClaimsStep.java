package works.repka.rysclaims.migrate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import works.repka.ryscore.migrate.Migrations;
import works.repka.ryscore.team.TeamsApi;
import works.repka.rysclaims.RYSClaims;
import works.repka.rysclaims.data.ClaimData;
import works.repka.rysclaims.server.ClaimService;
import works.repka.rysclaims.server.ClaimsServer;
import works.repka.rysclaims.server.Loader;
import works.repka.rysclaims.server.ServerConfig;
import works.repka.rysclaims.server.Teams;

/**
 * Шаг переезда {@code claims} (порядок 30, сервер): земля FTB Chunks в пустую базу RYSClaims (план, раздел 10.5). Порядок,
 * флаг сборки, копию FTB, отметку и отчёт ведёт ядро ({@code migrate.Migrations}); вручную {@code /rys migrate run claims}
 * или {@code /claims admin import ftb}.
 *
 * <p>Шаг считает всё в памяти ({@link FtbClaims}), кладёт базу одним сохранением и только потом ставит билеты. Шаг идёт без
 * шага команд: UUID команд у FTB и RYSTeams одни.
 */
public final class ClaimsStep implements Migrations.Step {
    public static final String ID = "claims";
    private static final String EDITS = "edits";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public int order() {
        return 30;
    }

    @Override
    public Migrations.Side side() {
        return Migrations.Side.SERVER;
    }

    @Override
    public String modId() {
        return RYSClaims.MODID;
    }

    @Override
    public boolean needed(Migrations.Context c) {
        Path world = c.worldDir();
        if (world == null || !Files.isDirectory(world.resolve("ftbchunks"))) return false;
        // пока FTB Chunks держит землю, сами не переезжаем; исключение: запасной выпуск, где FTB Chunks остаётся картой
        if (c.isLoaded("ftbchunks") && !c.flag().bool("ftbChunksAsMap", false)) {
            c.log("FTB Chunks is still loaded: land migrates only without it or with the ftbChunksAsMap flag");
            return false;
        }
        if (!ClaimsServer.running()) return false;
        if (!ClaimsServer.service().data().isEmpty()) {
            c.log("RYSClaims land already exists: migration not needed");
            return false;
        }
        return true;
    }

    static List<Path> configs(Migrations.Context c, Path world) {
        return List.of(world.resolve("serverconfig/ftbchunks-world.snbt"), c.configDir().resolve("ftbchunks-world.snbt"));
    }

    @Override
    public Migrations.Report run(Migrations.Context c) throws Exception {
        Path world = c.worldDir();
        if (world == null) throw new IllegalStateException("no world folder");
        ClaimService service = ClaimsServer.service();
        FtbWorld w = FtbWorld.read(world, configs(c, world));
        boolean untouched = ServerConfig.untouched();
        FtbClaims.Plan plan = FtbClaims.plan(w, ServerConfig.snapshot(), untouched, Teams.api(), TeamsApi.get().teams());
        ClaimData live = service.data();
        if (!live.isEmpty()) throw new IllegalStateException("RYSClaims land already exists, the migration would overwrite it");
        live.replaceWith(plan.data);
        service.rebuildPlots();
        if (untouched) {
            ServerConfig.write(plan.settings);
            plan.report.line(w.configFile == null
                ? Say.t("settings_moved_nofile", "FTB Chunks settings moved to rysclaims-server.toml (no FTB file: our defaults)")
                : Say.t("settings_moved", "FTB Chunks settings moved to rysclaims-server.toml"));
        } else {
            plan.report.line(Say.t("settings_kept", "rysclaims-server.toml was already edited: FTB settings were not migrated"));
        }
        live.defaults(ServerConfig.snapshot().defaultRights);
        ClaimsServer.saveNow();
        c.data().addProperty(EDITS, live.edits());
        // необратимое после записи: билеты прогрузки
        if (c.server() != null) Loader.sync(c.server(), service);
        if (c.isLoaded("ftbchunks")) {
            plan.report.line(Say.t("ftb_as_map", "FTB Chunks is loaded (ftbChunksAsMap): drop its chunk loading with /ftbchunks admin unload_everything and enable disable_protection"));
        }
        return plan.report;
    }

    @Override
    public Migrations.Report undo(Migrations.Context c) throws Exception {
        if (!ClaimsServer.running()) throw new IllegalStateException("land server is not running");
        ClaimService service = ClaimsServer.service();
        ClaimData live = service.data();
        long at = c.data().has(EDITS) ? c.data().get(EDITS).getAsLong() : live.edits();
        if (live.edits() != at) {
            throw new IllegalStateException("land was edited " + (live.edits() - at) + " times after the migration: undo would erase the players' new claims");
        }
        int chunks = live.total();
        live.clear();
        ClaimsServer.saveNow();
        if (c.server() != null) Loader.sync(c.server(), service);
        return Migrations.Report.create().count("chunks", chunks)
            .line(Say.t("undo_done", "RYSClaims land cleared; rysclaims-server.toml is not rolled back, FTB sources were not touched"));
    }
}

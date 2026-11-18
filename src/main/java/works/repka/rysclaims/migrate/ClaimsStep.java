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
            c.log("FTB Chunks ещё загружен: перенос земли только без него или с флагом ftbChunksAsMap");
            return false;
        }
        if (!ClaimsServer.running()) return false;
        if (!ClaimsServer.service().data().isEmpty()) {
            c.log("земля RYSClaims уже есть: перенос не нужен");
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
        if (world == null) throw new IllegalStateException("нет папки мира");
        ClaimService service = ClaimsServer.service();
        FtbWorld w = FtbWorld.read(world, configs(c, world));
        boolean untouched = ServerConfig.untouched();
        FtbClaims.Plan plan = FtbClaims.plan(w, ServerConfig.snapshot(), untouched, Teams.api(), TeamsApi.get().teams());
        ClaimData live = service.data();
        if (!live.isEmpty()) throw new IllegalStateException("земля RYSClaims уже есть, перенос затёр бы её");
        live.replaceWith(plan.data);
        if (untouched) {
            ServerConfig.write(plan.settings);
            plan.report.line("настройки FTB Chunks перенесены в rysclaims-server.toml" + (w.configFile == null ? " (файла FTB нет: наши по умолчанию)" : ""));
        } else {
            plan.report.line("rysclaims-server.toml уже правили: настройки FTB не переносились");
        }
        live.defaults(ServerConfig.snapshot().defaultRights);
        ClaimsServer.saveNow();
        c.data().addProperty(EDITS, live.edits());
        // необратимое после записи: билеты прогрузки
        if (c.server() != null) Loader.sync(c.server(), service);
        if (c.isLoaded("ftbchunks")) {
            plan.report.line("FTB Chunks загружен (ftbChunksAsMap): сними его прогрузку /ftbchunks admin unload_everything и включи disable_protection");
        }
        return plan.report;
    }

    @Override
    public Migrations.Report undo(Migrations.Context c) throws Exception {
        if (!ClaimsServer.running()) throw new IllegalStateException("сервер земли не запущен");
        ClaimService service = ClaimsServer.service();
        ClaimData live = service.data();
        long at = c.data().has(EDITS) ? c.data().get(EDITS).getAsLong() : live.edits();
        if (live.edits() != at) {
            throw new IllegalStateException("после переезда землю правили " + (live.edits() - at) + " раз: откат стёр бы новые клеймы игроков");
        }
        int chunks = live.total();
        live.clear();
        ClaimsServer.saveNow();
        if (c.server() != null) Loader.sync(c.server(), service);
        return Migrations.Report.create().count("chunks", chunks)
            .line("земля RYSClaims очищена; rysclaims-server.toml не откатывается, исходники FTB не трогались");
    }
}

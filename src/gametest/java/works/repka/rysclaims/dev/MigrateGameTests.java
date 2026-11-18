package works.repka.rysclaims.dev;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.stream.Stream;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ForcedChunksSavedData;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import works.repka.ryscore.migrate.Migrations;
import works.repka.rysclaims.RYSClaims;
import works.repka.rysclaims.data.Claim;
import works.repka.rysclaims.data.ClaimData;
import works.repka.rysclaims.data.Settings;
import works.repka.rysclaims.data.TeamLand;
import works.repka.rysclaims.migrate.ClaimsStep;
import works.repka.rysclaims.server.ClaimService;
import works.repka.rysclaims.server.ClaimsServer;
import works.repka.rysclaims.server.Loader;
import works.repka.rysclaims.server.ServerConfig;

/**
 * Шаг переезда {@code claims} на тестовом сервере (этап З3): заготовки FTB кладутся в мир теста, шаг идёт как
 * {@code /rys migrate run claims}; после него билеты стоят на всех перенесённых прогруженных чанках и ни на одном лишнем;
 * откат после правки земли отказан, без правки возвращает пустую базу. Земля других тестов на время прячется и
 * возвращается: всё идёт в одном тике сервера.
 */
@GameTestHolder(RYSClaims.MODID)
@PrefixGameTestTemplate(false)
public final class MigrateGameTests {
    private MigrateGameTests() {}

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void migrateClaims(GameTestHelper h) {
        MinecraftServer server = h.getLevel().getServer();
        ClaimService s = ClaimsServer.service();
        ClaimData live = s.data();
        ClaimData others = new ClaimData(Settings.defaultRights());
        others.replaceWith(live);
        live.clear();
        Settings before = ServerConfig.snapshot();
        try {
            Path world = server.getWorldPath(LevelResource.ROOT);
            copy(Path.of(System.getProperty("rysclaims.samples")).resolve("world"), world);

            Migrations.Outcome o = Migrations.runManual(ClaimsStep.ID, server);
            if (o.status() != Migrations.Status.DONE) h.fail("шаг: " + o);
            if (live.total() != 9) h.fail("перенесено " + live.total() + " чанков вместо 9");

            // режим «всегда» у всех команд: билеты должны совпасть с включённой прогрузкой ровно
            Settings always = ServerConfig.snapshot();
            always.maxLoad = Settings.LoadMode.ALWAYS;
            ServerConfig.write(always);
            for (TeamLand land : live.lands().values()) land.loadMode = Settings.LoadMode.ALWAYS;
            Loader.sync(server, s);
            for (String dim : live.dimensions()) {
                ServerLevel level = server.getLevel(Loader.key(dim));
                LongSet want = new LongOpenHashSet();
                for (Long2ObjectMap.Entry<Claim> e : live.claims(dim).long2ObjectEntrySet()) if (e.getValue().force()) want.add(e.getLongKey());
                LongSet have = tickets(level);
                if (!have.equals(want)) h.fail(dim + ": билеты " + have + ", прогрузка " + want);
            }

            // откат после правки земли отказан
            UUID team = live.lands().keySet().iterator().next();
            s.claim(null, team, "minecraft:overworld", LongArrayList.of(works.repka.rysclaims.data.Chunks.pack(500, 500)), true);
            Migrations.Outcome refused = Migrations.undo(ClaimsStep.ID, server);
            if (refused.status() != Migrations.Status.FAILED) h.fail("откат после правки прошёл: " + refused);
            s.release(null, team, "minecraft:overworld", LongArrayList.of(works.repka.rysclaims.data.Chunks.pack(500, 500)), true);
        } finally {
            ServerConfig.write(before);
            live.clear();
            live.replaceWith(others);
            Loader.sync(server, s);
        }
        for (ServerLevel level : server.getAllLevels()) {
            // после возврата чужой земли наших билетов от переезда не осталось
            for (long chunk : tickets(level)) {
                Claim c = live.get(Loader.id(level.dimension()), chunk);
                if (c == null || !c.force()) h.fail("лишний билет " + chunk + " в " + level.dimension().location());
            }
        }
        h.succeed();
    }

    private static LongSet tickets(ServerLevel level) {
        LongSet out = new LongOpenHashSet();
        ForcedChunksSavedData data = level.getDataStorage().get(ForcedChunksSavedData.factory(), ForcedChunksSavedData.FILE_ID);
        if (data == null) return out;
        for (LongSet set : data.getEntityForcedChunks().getTickingChunks().values()) out.addAll(set);
        return out;
    }

    private static void copy(Path from, Path to) {
        try (Stream<Path> s = Files.walk(from)) {
            for (Path src : s.toList()) {
                Path dst = to.resolve(from.relativize(src).toString());
                if (Files.isDirectory(src)) Files.createDirectories(dst);
                else Files.copy(src, dst, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}

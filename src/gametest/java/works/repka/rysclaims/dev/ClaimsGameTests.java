package works.repka.rysclaims.dev;

import it.unimi.dsi.fastutil.longs.LongSet;
import java.util.UUID;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ForcedChunksSavedData;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import works.repka.ryscore.claims.ClaimsApi;
import works.repka.rysclaims.RYSClaims;
import works.repka.rysclaims.data.Chunks;
import works.repka.rysclaims.server.ClaimService;
import works.repka.rysclaims.server.ClaimsServer;
import works.repka.rysclaims.server.Loader;

/**
 * Проверки на тестовом сервере без окна ({@code gradlew runGameTestServer}): команды {@code /claims} доходят до сервиса,
 * билет прогрузки ставится и снимается, «пока кто-то в сети» снимает билет после выхода игрока, контракт ядра отвечает.
 * Перезапуск сервера здесь не проверяется: проверку при загрузке мира покрывает {@code LoadPlanTest}.
 */
@GameTestHolder(RYSClaims.MODID)
@PrefixGameTestTemplate(false)
public final class ClaimsGameTests {
    private ClaimsGameTests() {}

    private static void run(ServerLevel level, CommandSourceStack src, String command) {
        level.getServer().getCommands().performPrefixedCommand(src, command);
    }

    private static boolean ticket(ServerLevel level, long chunk) {
        ForcedChunksSavedData data = level.getDataStorage().get(ForcedChunksSavedData.factory(), ForcedChunksSavedData.FILE_ID);
        if (data == null) return false;
        for (LongSet set : data.getEntityForcedChunks().getTickingChunks().values()) if (set.contains(chunk)) return true;
        return false;
    }

    private static void check(GameTestHelper h, boolean ok, String what) {
        if (!ok) h.fail(what);
    }

    @GameTest(template = "empty", timeoutTicks = 400)
    public static void commandsAndTickets(GameTestHelper h) {
        if (CompatRuns.skipMockPlayers(h, "commandsAndTickets")) return;
        ServerLevel level = h.getLevel();
        String dim = Loader.id(level.dimension());
        ServerPlayer player = h.makeMockServerPlayerInLevel();
        // далеко от площадки теста: чанк сам по себе не загружен
        player.moveTo(3000.5, 100, 3000.5);
        UUID team = player.getUUID(); // без RYSTeams игрок соло
        long center = Chunks.pack(3000 >> 4, 3000 >> 4);
        CommandSourceStack src = player.createCommandSourceStack();
        ClaimService s = ClaimsServer.service();

        h.startSequence()
            .thenExecute(() -> {
                check(h, ClaimsApi.get().present(), "контракт ядра не отдан RYSClaims");
                run(level, src, "claims claim 1");
                check(h, s.data().claimed(team) == 9, "занято " + s.data().claimed(team) + " вместо 9");
                check(h, team.equals(ClaimsApi.get().ownerAt(level.dimension(), 3000 >> 4, 3000 >> 4)), "ownerAt не видит землю");
                run(level, src, "claims load");
                check(h, s.data().forced(team) == 1, "прогружено " + s.data().forced(team));
            })
            .thenIdle(3)
            .thenExecute(() -> {
                check(h, ticket(level, center), "билет прогрузки не поставлен");
                check(h, Loader.applied().getOrDefault(dim, it.unimi.dsi.fastutil.longs.Long2ObjectMaps.emptyMap()).containsKey(center), "билет не учтён");
                check(h, ClaimsApi.get().forceLoaded(level.dimension(), 3000 >> 4, 3000 >> 4), "forceLoaded ложь");
                run(level, src, "claims unload");
            })
            .thenIdle(3)
            .thenExecute(() -> {
                check(h, !ticket(level, center), "билет не снят после unload");
                run(level, src, "claims load");
            })
            .thenIdle(3)
            .thenExecute(() -> {
                check(h, ticket(level, center), "билет не вернулся");
                // игрок вышел: режим «пока кто-то в сети» снимает билет, прогрузка остаётся включённой
                level.getServer().getPlayerList().remove(player);
            })
            .thenIdle(3)
            .thenExecute(() -> {
                check(h, !ticket(level, center), "билет держится после выхода игрока");
                check(h, s.data().forced(team) == 1, "выход игрока выключил прогрузку");
                // освобождение с отсчётом и «Оставить»
                run(level, src, "claims release 1");
                check(h, s.data().claimed(team) == 9, "освобождение без отсчёта");
                check(h, s.releasing(team)[0] == 9, "отсчёт не идёт");
                run(level, src, "claims keep");
                check(h, s.releasing(team)[0] == 0, "keep не отменил освобождение");
                // убрать за собой сразу: release идёт с отсчётом, и земля пережила бы остановку сервера в rysclaims.dat;
                // следующий прогон в том же мире получил бы «чужая команда» вместо 9 чанков
                for (long chunk : Chunks.square(3000 >> 4, 3000 >> 4, 1)) s.data().remove(dim, chunk);
                check(h, s.data().claimed(team) == 0, "земля теста не убрана: " + s.data().claimed(team));
                Loader.sync(level.getServer(), s);
            })
            .thenSucceed();
    }
}

package works.repka.rysclaims.dev;

import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.fml.ModList;
import works.repka.rysclaims.RYSClaims;

/**
 * Запуск с подложенными модами ({@code -PcompatMods=}): Create шлёт свои пакеты всем игрокам, а игрок-заглушка GameTest
 * их не принимает, и сервер падает. В таком запуске тесты с игроком-заглушкой пропускаются (их проверяет обычный запуск).
 */
final class CompatRuns {
    private CompatRuns() {}

    static boolean skipMockPlayers(GameTestHelper h, String name) {
        if (!ModList.get().isLoaded("create")) return false;
        RYSClaims.LOG.info("[rysclaims-test] {}: пропущен, с Create игрок-заглушка роняет сервер", name);
        h.succeed();
        return true;
    }
}

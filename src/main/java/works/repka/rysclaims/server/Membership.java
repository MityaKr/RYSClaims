package works.repka.rysclaims.server;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import works.repka.ryscore.team.TeamsApi;
import works.repka.rysclaims.net.ClaimsNet;

/**
 * Земля при событиях команды (план, раздел 8, этап З11): вопрос о личной земле при вступлении (макет {@code 45}),
 * «Забрать свои чанки» при уходе, предпросмотр слияния. Вопросы уходят игроку уведомлением с двумя кнопками
 * ({@code Question}), ответ приходит пакетом {@code Answer}; без мода на клиенте те же ответы дают команды
 * {@code /claims personal give|keep} и {@code /claims take}.
 */
public final class Membership {
    public static final int JOIN = 0, LEAVE = 1;

    /** Кто откуда ушёл и когда: забрать свои чанки можно час. */
    private record Left(UUID team, long at) {}

    private static final Map<UUID, Left> LEFT = new HashMap<>();

    private Membership() {}

    /** Игрок вступил: спросить о личной земле, если она есть. */
    static void joined(UUID team, UUID player) {
        LEFT.remove(player);
        ask(player);
    }

    /** Вход в игру: вопрос о личной земле, если игрок не ответил. */
    static void login(ServerPlayer p) {
        ask(p.getUUID());
    }

    private static void ask(UUID player) {
        if (!ClaimsServer.running()) return;
        ClaimService s = ClaimsServer.service();
        if (!s.personalPending(player)) return;
        MinecraftServer server = ClaimsServer.server();
        ServerPlayer p = server == null ? null : server.getPlayerList().getPlayer(player);
        if (p == null) return;
        UUID team = s.teams().teamOf(player);
        int n = s.data().claimed(player);
        boolean fits = s.data().claimed(team) + n <= s.limits(team).claims;
        if (ClaimsNet.accepts(p)) ClaimsNet.send(p, new ClaimsNet.Question(JOIN, n, fits, Commands.teamName(s, team).getString()));
        else p.sendSystemMessage(Msg.t("personal.ask", "You have personal land: %s chunks. /claims personal give gives it to the team, /claims personal keep keeps it", n));
    }

    /** Игрок ушёл: предложить забрать чанки, которые он принёс. */
    static void left(UUID team, UUID player) {
        if (!ClaimsServer.running() || team.equals(player)) return;
        ClaimService s = ClaimsServer.service();
        int n = 0;
        for (var l : s.broughtBy(team, player).values()) n += l.size();
        if (n == 0) return;
        LEFT.put(player, new Left(team, System.currentTimeMillis()));
        MinecraftServer server = ClaimsServer.server();
        ServerPlayer p = server == null ? null : server.getPlayerList().getPlayer(player);
        if (p == null) return;
        if (ClaimsNet.accepts(p)) ClaimsNet.send(p, new ClaimsNet.Question(LEAVE, n, true, Commands.teamName(s, team).getString()));
        else p.sendSystemMessage(Msg.t("take.ask", "You brought %s chunks to the team. /claims take takes them back within an hour", n));
    }

    /** Ответ игрока на вопрос (кнопка уведомления или команда). Возвращает строку итога для игрока. */
    public static Component answer(UUID player, int kind, boolean yes) {
        ClaimService s = ClaimsServer.service();
        if (kind == JOIN) {
            if (!s.personalPending(player) && s.data().claimed(player) == 0) return Msg.t("personal.none", "You have no personal land");
            if (!yes) {
                s.keepPersonal(player);
                return Msg.t("personal.kept", "Your land stays personal; nobody can change it while you are in a team");
            }
            UUID team = s.teams().teamOf(player);
            int n = s.data().claimed(player);
            if (!s.joined(team, player)) return Msg.t("personal.no_room", "The team has no room for %s more chunks", n);
            ClaimSync.touch(team);
            Feed.post(team, Component.translatableWithFallback("rysclaims.feed.gave", "%s gave personal land to the team: %s chunks",
                Commands.playerName(ClaimsServer.server(), player), n), false);
            return Msg.t("personal.given", "Personal land given to the team: %s chunks", n);
        }
        Left l = LEFT.get(player);
        if (!yes || l == null || System.currentTimeMillis() - l.at > ClaimService.TAKE_MS) {
            LEFT.remove(player);
            return Msg.t("take.none", "Nothing to take back");
        }
        LEFT.remove(player);
        int n = s.takeBack(player, l.team);
        if (n > 0) {
            Feed.post(l.team, Component.translatableWithFallback("rysclaims.feed.took", "%s took back own chunks: %s",
                Commands.playerName(ClaimsServer.server(), player), n), false);
        }
        return n > 0 ? Msg.t("take.done", "Taken back: %s chunks", n) : Msg.t("take.no_room", "No room in your personal limit");
    }

    public static void onAnswer(ServerPlayer player, ClaimsNet.Answer a) {
        if (!ClaimsServer.running() || (a.kind() != JOIN && a.kind() != LEAVE)) return;
        player.sendSystemMessage(answer(player.getUUID(), a.kind(), a.yes()));
    }

    /** Строка предпросмотра слияния у лидеров (план, раздел 8): «Земля: 61 чанк перейдёт команде, лимита хватает». */
    static List<TeamsApi.MergeLine> preview(UUID from, UUID into) {
        if (!ClaimsServer.running()) return List.of();
        int[] p = ClaimsServer.service().mergePreview(from, into);
        if (p[0] == 0) return List.of();
        Component text = p[1] == 0
            ? Component.translatableWithFallback("rysclaims.merge.fits", "Land: %s chunks will go to the team, the limit is enough", p[0])
            : Component.translatableWithFallback("rysclaims.merge.over", "Land: %s chunks will go to the team, %s over the limit: the extra land stays 7 days, no new claims", p[0], p[1]);
        return List.of(new TeamsApi.MergeLine(Feed.SOURCE, text, p[1] > 0));
    }

    static void clear() {
        LEFT.clear();
    }
}

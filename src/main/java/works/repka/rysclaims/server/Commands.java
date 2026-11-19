package works.repka.rysclaims.server;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.LongList;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.GameProfileArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;
import works.repka.ryscore.migrate.Migrations;
import works.repka.ryscore.team.TeamsApi;
import works.repka.rysclaims.data.Chunks;
import works.repka.rysclaims.data.Claim;
import works.repka.rysclaims.data.Limits;
import works.repka.rysclaims.data.PlayerBonus;
import works.repka.rysclaims.data.Plots;
import works.repka.rysclaims.data.Rights;
import works.repka.rysclaims.data.Settings;
import works.repka.rysclaims.data.TeamLand;
import works.repka.rysclaims.migrate.ClaimsStep;

/**
 * Команды {@code /claims} первого выпуска (план, раздел 6). Каждая проверяет те же права, что и окно: право управления
 * землёй из таблицы прав RYSTeams, лимиты, измерение. Раздел {@code admin} требует уровня оператора 2 и идёт мимо прав и
 * лимитов, но не мимо чужой земли.
 */
public final class Commands {
    /** Самый большой радиус квадрата: 31×31 = 961 чанк, меньше потолка в 1024 чанка на правку. */
    public static final int MAX_RADIUS = 15;
    private static final SimpleCommandExceptionType NOT_RUNNING =
        new SimpleCommandExceptionType(Msg.t("error.not_running", "Land is not loaded yet"));
    private static final SimpleCommandExceptionType NO_TEAM =
        new SimpleCommandExceptionType(Msg.t("error.no_team", "No such team or player"));

    /** Команды и игроки в сети; имя с пробелами или не латиницей в кавычках. */
    private static final SuggestionProvider<CommandSourceStack> PLAYERS = (c, b) -> {
        List<String> out = new ArrayList<>();
        if (ClaimsServer.running()) for (String n : teamNames(ClaimsServer.service()).values()) out.add(quoted(n));
        for (String n : c.getSource().getServer().getPlayerList().getPlayerNamesArray()) out.add(n);
        return SharedSuggestionProvider.suggest(out, b);
    };

    private static String quoted(String name) {
        for (int i = 0; i < name.length(); i++) {
            if (!com.mojang.brigadier.StringReader.isAllowedInUnquotedString(name.charAt(i))) return "\"" + name.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
        }
        return name;
    }

    private Commands() {}

    public static void register(CommandDispatcher<CommandSourceStack> d) {
        LiteralArgumentBuilder<CommandSourceStack> root = net.minecraft.commands.Commands.literal("claims");
        root.then(radius("claim", (c, r) -> edit(c, own(c), Op.CLAIM, r, false)));
        root.then(radius("release", (c, r) -> edit(c, own(c), Op.RELEASE, r, false)));
        root.then(radius("load", (c, r) -> edit(c, own(c), Op.LOAD, r, false)));
        root.then(radius("unload", (c, r) -> edit(c, own(c), Op.UNLOAD, r, false)));
        root.then(lit("keep").executes(c -> keep(c, own(c), false)));
        root.then(lit("loadmode")
            .then(lit("online").executes(c -> loadMode(c, Settings.LoadMode.ONLINE)))
            .then(lit("always").executes(c -> loadMode(c, Settings.LoadMode.ALWAYS))));
        root.then(lit("info").executes(Commands::info));
        root.then(lit("list").executes(c -> list(c, own(c))));
        root.then(lit("personal")
            .then(lit("give").executes(c -> answer(c, Membership.JOIN, true)))
            .then(lit("keep").executes(c -> answer(c, Membership.JOIN, false))));
        root.then(lit("take").executes(c -> answer(c, Membership.LEAVE, true)));
        root.then(lit("name").executes(c -> rename(c, ""))
            .then(net.minecraft.commands.Commands.argument("name", StringArgumentType.greedyString())
                .executes(c -> rename(c, StringArgumentType.getString(c, "name")))));
        root.then(lit("log").executes(c -> log(c, own(c), 1))
            .then(net.minecraft.commands.Commands.argument("page", IntegerArgumentType.integer(1, 100))
                .executes(c -> log(c, own(c), IntegerArgumentType.getInteger(c, "page")))));
        root.then(lit("trust").then(net.minecraft.commands.Commands.argument("player", GameProfileArgument.gameProfile())
            .executes(c -> trust(c, true))));
        root.then(lit("untrust").then(net.minecraft.commands.Commands.argument("player", GameProfileArgument.gameProfile())
            .executes(c -> trust(c, false))));
        root.then(rights());
        root.then(flags());
        root.then(admin());
        d.register(root);
    }

    // ---- построители ----

    private static LiteralArgumentBuilder<CommandSourceStack> lit(String name) {
        return net.minecraft.commands.Commands.literal(name);
    }

    private interface RadiusAction {
        int run(CommandContext<CommandSourceStack> c, int radius) throws CommandSyntaxException;
    }

    private static LiteralArgumentBuilder<CommandSourceStack> radius(String name, RadiusAction action) {
        return lit(name).executes(c -> action.run(c, 0))
            .then(net.minecraft.commands.Commands.argument("radius", IntegerArgumentType.integer(0, MAX_RADIUS))
                .executes(c -> action.run(c, IntegerArgumentType.getInteger(c, "radius"))));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> rights() {
        var node = lit("rights").executes(Commands::showRights);
        var preset = lit("preset");
        for (Rights.Preset p : Rights.Preset.values()) preset.then(lit(p.key()).executes(c -> setPreset(c, p)));
        node.then(preset);
        for (Rights.Action a : Rights.Action.values()) {
            var action = lit(a.key());
            for (String column : List.of("team", Rights.ALLIES, Rights.ALL, Rights.OFFICER, Rights.MEMBER, Rights.GUEST)) {
                action.then(lit(column)
                    .then(lit("on").executes(c -> setRight(c, a, column, true)))
                    .then(lit("off").executes(c -> setRight(c, a, column, false))));
            }
            node.then(action);
        }
        return node;
    }

    private static LiteralArgumentBuilder<CommandSourceStack> flags() {
        var node = lit("flag");
        for (TeamLand.Flag f : TeamLand.Flag.values()) {
            node.then(lit(f.key)
                .then(lit("on").executes(c -> setFlag(c, f, true)))
                .then(lit("off").executes(c -> setFlag(c, f, false))));
        }
        var vis = lit("visibility");
        for (TeamLand.Visibility v : TeamLand.Visibility.values()) vis.then(lit(v.key()).executes(c -> setVisibility(c, v)));
        node.then(vis);
        return node;
    }

    private static LiteralArgumentBuilder<CommandSourceStack> admin() {
        var node = lit("admin").requires(s -> s.hasPermission(2));
        node.then(lit("bypass").executes(Commands::bypass));
        var team = net.minecraft.commands.Commands.argument("team", StringArgumentType.string()).suggests(PLAYERS);
        team.then(radius("claim", (c, r) -> edit(c, team(c), Op.CLAIM, r, true)));
        team.then(radius("release", (c, r) -> edit(c, team(c), Op.RELEASE, r, true)));
        team.then(radius("load", (c, r) -> edit(c, team(c), Op.LOAD, r, true)));
        team.then(radius("unload", (c, r) -> edit(c, team(c), Op.UNLOAD, r, true)));
        team.then(lit("keep").executes(c -> keep(c, team(c), true)));
        team.then(lit("list").executes(c -> list(c, team(c))));
        team.then(lit("log").executes(c -> log(c, team(c), 1))
            .then(net.minecraft.commands.Commands.argument("page", IntegerArgumentType.integer(1, 100))
                .executes(c -> log(c, team(c), IntegerArgumentType.getInteger(c, "page")))));
        node.then(lit("for").then(team));
        node.then(lit("limit").then(net.minecraft.commands.Commands.argument("team", StringArgumentType.string()).suggests(PLAYERS)
            .then(lit("claims").then(net.minecraft.commands.Commands.argument("n", IntegerArgumentType.integer(-1_000_000, 1_000_000))
                .executes(c -> teamBonus(c, true))))
            .then(lit("force").then(net.minecraft.commands.Commands.argument("n", IntegerArgumentType.integer(-1_000_000, 1_000_000))
                .executes(c -> teamBonus(c, false))))));
        node.then(lit("bonus").then(net.minecraft.commands.Commands.argument("player", GameProfileArgument.gameProfile())
            .then(lit("claims").then(net.minecraft.commands.Commands.argument("n", IntegerArgumentType.integer(-1_000_000, 1_000_000))
                .executes(c -> playerBonus(c, true))))
            .then(lit("force").then(net.minecraft.commands.Commands.argument("n", IntegerArgumentType.integer(-1_000_000, 1_000_000))
                .executes(c -> playerBonus(c, false))))));
        node.then(lit("loaded").executes(Commands::loaded));
        node.then(lit("import").requires(s -> s.hasPermission(4))
            .then(lit("ftb").executes(c -> imported(c, false)))
            .then(lit("undo").executes(c -> imported(c, true))));
        return node;
    }

    // ---- общее ----

    private static ClaimService service() throws CommandSyntaxException {
        if (!ClaimsServer.running()) throw NOT_RUNNING.create();
        return ClaimsServer.service();
    }

    private static UUID own(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        return service().teams().teamOf(c.getSource().getPlayerOrException().getUUID());
    }

    /** Команда по UUID, по точному имени команды, по нику игрока (его команда), по имени команды без учёта регистра ({@link TeamLookup}). */
    private static UUID team(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        String arg = StringArgumentType.getString(c, "team");
        ClaimService s = service();
        MinecraftServer server = c.getSource().getServer();
        UUID found = TeamLookup.resolve(arg, teamNames(s), name -> {
            ServerPlayer online = server.getPlayerList().getPlayerByName(name);
            if (online != null) return s.teams().teamOf(online.getUUID());
            var cache = server.getProfileCache();
            if (cache == null) return null;
            return cache.get(name).map(p -> s.teams().teamOf(p.getId())).orElse(null);
        });
        if (found == null) throw NO_TEAM.create();
        return found;
    }

    /** Имена команд RYSTeams и команд FTB без пары. */
    private static Map<UUID, String> teamNames(ClaimService s) {
        Map<UUID, String> names = new LinkedHashMap<>();
        for (UUID t : TeamsApi.get().teams()) names.put(t, TeamsApi.get().name(t).getString());
        for (TeamLand land : s.data().lands().values()) if (land.legacyName != null) names.putIfAbsent(land.id, land.legacyName);
        return names;
    }

    public static Component teamName(ClaimService s, UUID team) {
        TeamLand land = s.data().land(team);
        if (land != null && land.legacyName != null) return Msg.t("team.legacy", "FTB team \"%s\"", land.legacyName);
        return TeamsApi.get().name(team);
    }

    private static @Nullable UUID actor(CommandSourceStack src) {
        return src.getEntity() instanceof ServerPlayer p ? p.getUUID() : null;
    }

    private static int fail(CommandSourceStack src, Component text) {
        src.sendFailure(text);
        return 0;
    }

    // ---- занять, освободить, прогрузить ----

    private enum Op { CLAIM, RELEASE, LOAD, UNLOAD }

    private static int edit(CommandContext<CommandSourceStack> c, UUID team, Op op, int radius, boolean admin) throws CommandSyntaxException {
        ClaimService s = service();
        CommandSourceStack src = c.getSource();
        String dim = Loader.id(src.getLevel().dimension());
        int cx = net.minecraft.util.Mth.floor(src.getPosition().x) >> 4, cz = net.minecraft.util.Mth.floor(src.getPosition().z) >> 4;
        LongList chunks = Chunks.square(cx, cz, radius);
        UUID actor = actor(src);
        ClaimService.Result r = switch (op) {
            case CLAIM -> s.claim(actor, team, dim, chunks, admin);
            case RELEASE -> s.release(actor, team, dim, chunks, admin);
            case LOAD -> s.load(actor, team, dim, chunks, true, admin);
            case UNLOAD -> s.load(actor, team, dim, chunks, false, admin);
        };
        Limits lim = s.limits(team);
        MutableComponent line = switch (op) {
            case CLAIM -> Msg.t("done.claim", "Claimed: %s. Land %s of %s", r.done, s.data().claimed(team), lim.claims);
            case RELEASE -> s.settings().releaseSeconds > 0
                ? Msg.t("done.release", "%s chunks will be released in %s s · /claims keep cancels", r.done, s.settings().releaseSeconds)
                : Msg.t("done.release_now", "Released: %s", r.done);
            case LOAD -> Msg.t("done.load", "Force loading on: %s. Loaded %s of %s", r.done, s.data().forced(team), lim.force);
            case UNLOAD -> Msg.t("done.unload", "Force loading off: %s. Loaded %s of %s", r.done, s.data().forced(team), lim.force);
        };
        if (!r.skipped.isEmpty()) line.append(" ").append(skipped(r));
        if (r.done == 0) return fail(src, line);
        if (admin) line.append(" · ").append(Msg.t("admin.for", "operator, team %s", teamName(s, team)));
        src.sendSuccess(() -> line, admin);
        return r.done;
    }

    static MutableComponent skipped(ClaimService.Result r) {
        MutableComponent why = Component.empty();
        boolean first = true;
        for (Map.Entry<ClaimService.Skip, Integer> e : r.skipped.entrySet()) {
            if (!first) why.append(", ");
            first = false;
            why.append(skipName(e.getKey())).append(" " + e.getValue());
        }
        return Msg.t("skipped", "Skipped %s (%s)", r.skippedTotal(), why).withStyle(ChatFormatting.GRAY);
    }

    private static Component skipName(ClaimService.Skip why) {
        return switch (why) {
            case NO_RIGHT -> Msg.t("skip.no_right", "no permission in your team");
            case DIMENSION -> Msg.t("skip.dimension", "land cannot be claimed in this dimension");
            case OTHER_TEAM -> Msg.t("skip.other_team", "someone else's land");
            case ALREADY -> Msg.t("skip.already", "already so");
            case LIMIT -> Msg.t("skip.limit", "claim limit");
            case FORCE_LIMIT -> Msg.t("skip.force_limit", "force loading limit");
            case NOT_ADJACENT -> Msg.t("skip.not_adjacent", "not next to your land");
            case NOT_CLAIMED -> Msg.t("skip.not_claimed", "unclaimed");
            case NOT_OWNER -> Msg.t("skip.not_owner", "not your land");
            case RELEASING -> Msg.t("skip.releasing", "being released");
            case LOCKED -> Msg.t("skip.locked", "locked by the server");
            case NOT_FOUND -> Msg.t("skip.not_found", "not found");
            case BUFFER -> Msg.t("skip.buffer", "too close to another team's land");
        };
    }

    private static int keep(CommandContext<CommandSourceStack> c, UUID team, boolean admin) throws CommandSyntaxException {
        ClaimService s = service();
        int n = s.keep(actor(c.getSource()), team, admin);
        if (n < 0) return fail(c.getSource(), Msg.t("error.no_right", "Your role in the team cannot do this"));
        if (n == 0) return fail(c.getSource(), Msg.t("keep.none", "Nothing is being released"));
        c.getSource().sendSuccess(() -> Msg.t("keep.done", "Kept: %s chunks", n), admin);
        return n;
    }

    private static int loadMode(CommandContext<CommandSourceStack> c, Settings.LoadMode mode) throws CommandSyntaxException {
        ClaimService s = service();
        UUID player = c.getSource().getPlayerOrException().getUUID();
        UUID team = s.teams().teamOf(player);
        if (!s.manages(player, team, ClaimService.PERM_FORCE)) return fail(c.getSource(), Msg.t("error.no_right", "Your role in the team cannot do this"));
        if (mode == Settings.LoadMode.ALWAYS && s.settings().maxLoad != Settings.LoadMode.ALWAYS) {
            return fail(c.getSource(), Msg.t("loadmode.locked", "The server allows force loading only while a team member is online"));
        }
        s.data().landOrCreate(team).loadMode = mode;
        ClaimsServer.loaderDirty();
        ClaimsServer.markDirty();
        c.getSource().sendSuccess(() -> mode == Settings.LoadMode.ALWAYS
            ? Msg.t("loadmode.always", "Force loaded chunks work always")
            : Msg.t("loadmode.online", "Force loaded chunks work while someone from the team is online"), false);
        return 1;
    }

    // ---- сведения ----

    private static int info(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        ClaimService s = service();
        CommandSourceStack src = c.getSource();
        String dim = Loader.id(src.getLevel().dimension());
        int cx = net.minecraft.util.Mth.floor(src.getPosition().x) >> 4, cz = net.minecraft.util.Mth.floor(src.getPosition().z) >> 4;
        long chunk = Chunks.pack(cx, cz);
        Claim claim = s.data().get(dim, chunk);
        if (claim == null) {
            MutableComponent line = Msg.t("info.free", "Chunk %s, %s: unclaimed land", cx, cz);
            if (!s.settings().claimable(dim)) line.append(" · ").append(Msg.t("info.no_claims_here", "land cannot be claimed in this dimension"));
            src.sendSuccess(() -> line, false);
            return 1;
        }
        MutableComponent line = Msg.t("info.owner", "Chunk %s, %s: land of %s", cx, cz, teamName(s, claim.team()));
        works.repka.rysclaims.data.Plot plot = s.plot(claim.team(), claim.plot());
        if (plot != null) line.append(" · ").append(plotName(plot));
        if (claim.by() != null) line.append(" · ").append(Msg.t("info.by", "claimed by %s", playerName(src.getServer(), claim.by())));
        if (claim.force()) line.append(" · ").append(Msg.t("info.loaded", "force loaded"));
        if (claim.releasing()) {
            long left = Math.max(0, (claim.releaseAt() - System.currentTimeMillis() + 999) / 1000);
            line.append(" · ").append(Msg.t("info.releasing", "released in %s s", left));
        }
        src.sendSuccess(() -> line, false);
        if (src.getEntity() instanceof ServerPlayer p) {
            UUID id = p.getUUID();
            MutableComponent can = Msg.t("info.you", "Here you may:");
            for (Rights.Action a : Rights.Action.values()) {
                can.append(" ").append(actionName(a)).append(" ").append(Msg.yesNo(s.can(id, dim, chunk, a)));
                if (a.ordinal() < Rights.Action.values().length - 1) can.append(",");
            }
            if (s.bypass(id)) can.append(" · ").append(Msg.t("info.bypass", "operator mode: protection off"));
            src.sendSuccess(() -> can.withStyle(ChatFormatting.GRAY), false);
        }
        return 1;
    }

    /** Ответ на вопрос о личной земле или «забрать свои чанки». */
    private static int answer(CommandContext<CommandSourceStack> c, int kind, boolean yes) throws CommandSyntaxException {
        service();
        UUID player = c.getSource().getPlayerOrException().getUUID();
        Component result = Membership.answer(player, kind, yes);
        c.getSource().sendSuccess(() -> result, false);
        return 1;
    }

    /** Имя участка; пустое это «Участок N». */
    static Component plotName(works.repka.rysclaims.data.Plot p) {
        return p.name.isEmpty() ? Msg.t("plot.unnamed", "Plot %s", p.id) : Component.literal(p.name);
    }

    static String playerName(net.minecraft.server.MinecraftServer server, UUID id) {
        var cache = server.getProfileCache();
        return cache == null ? id.toString().substring(0, 8) : cache.get(id).map(GameProfile::getName).orElse(id.toString().substring(0, 8));
    }

    /** {@code /claims name [имя]}: переименовать участок под ногами (право «занимать и освобождать»); без имени «Участок N». */
    private static int rename(CommandContext<CommandSourceStack> c, String name) throws CommandSyntaxException {
        ClaimService s = service();
        CommandSourceStack src = c.getSource();
        UUID player = src.getPlayerOrException().getUUID();
        String dim = Loader.id(src.getLevel().dimension());
        long chunk = Chunks.pack(net.minecraft.util.Mth.floor(src.getPosition().x) >> 4, net.minecraft.util.Mth.floor(src.getPosition().z) >> 4);
        Claim claim = s.data().get(dim, chunk);
        UUID team = s.teams().teamOf(player);
        if (claim == null || !claim.team().equals(team)) {
            throw new SimpleCommandExceptionType(Msg.t("error.not_own_plot", "Stand on your team's land")).create();
        }
        if (!s.renamePlot(player, team, claim.plot(), name, s.bypass(player))) {
            throw new SimpleCommandExceptionType(Msg.t("error.no_right", "Your role in the team cannot do this")).create();
        }
        works.repka.rysclaims.data.Plot p = s.plot(team, claim.plot());
        src.sendSuccess(() -> Msg.t("plot.renamed", "Plot renamed: %s", p == null ? Component.literal("?") : plotName(p)), false);
        return 1;
    }

    static Component actionName(Rights.Action a) {
        return switch (a) {
            case BUILD -> Msg.t("action.build", "build");
            case OPEN -> Msg.t("action.open", "open");
            case USE -> Msg.t("action.use", "doors and buttons");
            case ENTITY -> Msg.t("action.entity", "animals and frames");
        };
    }

    private static int list(CommandContext<CommandSourceStack> c, UUID team) throws CommandSyntaxException {
        ClaimService s = service();
        CommandSourceStack src = c.getSource();
        Limits lim = s.limits(team);
        src.sendSuccess(() -> Msg.t("list.head", "Land of %s: %s of %s chunks, force loading %s of %s",
            teamName(s, team), s.data().claimed(team), lim.claims, s.data().forced(team), lim.force), false);
        for (String dim : s.data().dimensions()) {
            List<LongList> groups = Plots.groups(s.data(), team, dim);
            if (groups.isEmpty()) continue;
            int count = 0;
            for (LongList g : groups) count += g.size();
            int total = count;
            src.sendSuccess(() -> Msg.t("list.dim", "%s: %s chunks in %s plots", dim, total, groups.size()), false);
            int shown = 0;
            for (LongList g : groups) {
                if (shown++ == 8) {
                    int rest = groups.size() - 8;
                    src.sendSuccess(() -> Msg.t("list.more", "  and %s more", rest).withStyle(ChatFormatting.GRAY), false);
                    break;
                }
                long center = Plots.center(g);
                int forced = 0;
                for (int i = 0; i < g.size(); i++) if (s.data().get(dim, g.getLong(i)).force()) forced++;
                int f = forced;
                works.repka.rysclaims.data.Plot plot = s.plotAt(dim, g.getLong(0));
                Component name = plot == null ? Component.literal("?") : plotName(plot);
                src.sendSuccess(() -> Msg.t("list.plot", "  %s: %s chunks near %s, %s (force loaded %s)",
                    name, g.size(), Chunks.x(center) * 16 + 8, Chunks.z(center) * 16 + 8, f).withStyle(ChatFormatting.GRAY), false);
            }
        }
        long[] rel = s.releasing(team);
        if (rel[0] > 0) {
            long left = Math.max(0, (rel[1] - System.currentTimeMillis() + 999) / 1000);
            src.sendSuccess(() -> Msg.t("list.releasing", "%s chunks are being released, first in %s s · /claims keep", rel[0], left), false);
        }
        return s.data().claimed(team);
    }

    // ---- права ----

    private static UUID rightsTeam(CommandContext<CommandSourceStack> c, ClaimService s) throws CommandSyntaxException {
        UUID player = c.getSource().getPlayerOrException().getUUID();
        UUID team = s.teams().teamOf(player);
        boolean admin = s.bypass(player);
        if (!s.editsRights(player, team, admin)) {
            throw new SimpleCommandExceptionType(s.settings().teamsEditRights
                ? Msg.t("error.no_right", "Your role in the team cannot do this")
                : Msg.t("error.rights_locked", "The server does not let teams change rights")).create();
        }
        return team;
    }

    private static int showRights(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        ClaimService s = service();
        UUID team = own(c);
        TeamLand land = s.data().land(team);
        Rights r = land == null ? s.settings().defaultRights : land.rights;
        Rights.Preset p = r.preset();
        c.getSource().sendSuccess(() -> Msg.t("rights.head", "Rights of %s: %s", teamName(s, team),
            p == null ? Msg.t("preset.custom", "custom") : presetName(p)), false);
        for (Rights.Action a : Rights.Action.values()) {
            c.getSource().sendSuccess(() -> Msg.t("rights.row", "  %s: team %s, allies %s, everyone %s", actionName(a),
                Msg.yesNo(r.allows(a, Rights.Relation.TEAM, Rights.MEMBER)),
                Msg.yesNo(r.allows(a, Rights.Relation.ALLY, null)),
                Msg.yesNo(r.allows(a, Rights.Relation.OTHER, null))).withStyle(ChatFormatting.GRAY), false);
        }
        if (land != null && !land.trusted.isEmpty()) {
            List<String> names = new ArrayList<>();
            var cache = c.getSource().getServer().getProfileCache();
            for (UUID u : land.trusted) names.add(cache == null ? u.toString() : cache.get(u).map(GameProfile::getName).orElse(u.toString()));
            c.getSource().sendSuccess(() -> Msg.t("rights.trusted", "  Trusted players: %s", String.join(", ", names)), false);
        }
        MutableComponent flags = Msg.t("rights.flags", "  Switches:");
        TeamLand l = land;
        for (TeamLand.Flag f : TeamLand.Flag.values()) flags.append(" " + f.key + " ").append(Msg.yesNo(s.flag(team, f)));
        flags.append(" visibility " + (l == null ? TeamLand.Visibility.ALL.key() : l.visibility.key()));
        c.getSource().sendSuccess(() -> flags.withStyle(ChatFormatting.GRAY), false);
        return 1;
    }

    static Component presetName(Rights.Preset p) {
        return switch (p) {
            case TEAM -> Msg.t("preset.team", "team only");
            case TEAM_AND_ALLIES -> Msg.t("preset.team_and_allies", "team and allies");
            case OPEN -> Msg.t("preset.open", "open base");
        };
    }

    private static int setPreset(CommandContext<CommandSourceStack> c, Rights.Preset p) throws CommandSyntaxException {
        ClaimService s = service();
        UUID team = rightsTeam(c, s);
        s.applyPreset(team, p);
        c.getSource().sendSuccess(() -> Msg.t("rights.preset_set", "Rights set: %s", presetName(p)), false);
        return 1;
    }

    private static int setRight(CommandContext<CommandSourceStack> c, Rights.Action a, String column, boolean on) throws CommandSyntaxException {
        ClaimService s = service();
        UUID team = rightsTeam(c, s);
        s.setRight(team, a, column, on);
        return showRights(c);
    }

    private static int setFlag(CommandContext<CommandSourceStack> c, TeamLand.Flag f, boolean on) throws CommandSyntaxException {
        ClaimService s = service();
        UUID team = rightsTeam(c, s);
        if (!s.setFlag(team, f, on)) throw new SimpleCommandExceptionType(Msg.t("error.flag_locked", "The server fixed this switch")).create();
        c.getSource().sendSuccess(() -> Msg.t("flag.set", "%s: %s", f.key, Msg.yesNo(on)), false);
        return 1;
    }

    private static int setVisibility(CommandContext<CommandSourceStack> c, TeamLand.Visibility v) throws CommandSyntaxException {
        ClaimService s = service();
        UUID team = rightsTeam(c, s);
        s.setVisibility(team, v);
        c.getSource().sendSuccess(() -> Msg.t("flag.set", "%s: %s", "visibility", v.key()), false);
        return 1;
    }

    private static int trust(CommandContext<CommandSourceStack> c, boolean on) throws CommandSyntaxException {
        ClaimService s = service();
        UUID team = rightsTeam(c, s);
        Collection<GameProfile> profiles = GameProfileArgument.getGameProfiles(c, "player");
        int n = 0;
        for (GameProfile p : profiles) if (s.trust(team, p.getId(), on)) n++;
        String names = String.join(", ", profiles.stream().map(GameProfile::getName).toList());
        c.getSource().sendSuccess(() -> on ? Msg.t("trust.on", "Trusted: %s", names) : Msg.t("trust.off", "No longer trusted: %s", names), false);
        return n;
    }

    // ---- оператор ----

    private static int bypass(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        ClaimService s = service();
        boolean on = s.toggleBypass(c.getSource().getPlayerOrException().getUUID());
        c.getSource().sendSuccess(() -> on
            ? Msg.t("bypass.on", "Operator mode: protection off for you")
            : Msg.t("bypass.off", "Operator mode off"), true);
        return 1;
    }

    private static int teamBonus(CommandContext<CommandSourceStack> c, boolean claims) throws CommandSyntaxException {
        ClaimService s = service();
        UUID team = team(c);
        int n = IntegerArgumentType.getInteger(c, "n");
        TeamLand land = s.data().landOrCreate(team);
        s.addBonus(team, claims ? n - land.bonusClaims : 0, claims ? 0 : n - land.bonusForce);
        Limits lim = s.limits(team);
        c.getSource().sendSuccess(() -> Msg.t("admin.limit", "Team %s bonus: claims %s, force loading %s. Limits now %s and %s",
            teamName(s, team), land.bonusClaims, land.bonusForce, lim.claims, lim.force), true);
        return 1;
    }

    private static int playerBonus(CommandContext<CommandSourceStack> c, boolean claims) throws CommandSyntaxException {
        ClaimService s = service();
        int n = IntegerArgumentType.getInteger(c, "n");
        int done = 0;
        for (GameProfile p : GameProfileArgument.getGameProfiles(c, "player")) {
            PlayerBonus b = s.data().bonusOrCreate(p.getId());
            s.addPlayerBonus(p.getId(), claims ? n - b.claims : 0, claims ? 0 : n - b.force);
            Limits lim = s.limits(s.teams().teamOf(p.getId()));
            c.getSource().sendSuccess(() -> Msg.t("admin.bonus", "Personal bonus of %s: claims %s, force loading %s. Team limits now %s and %s",
                p.getName(), b.claims, b.force, lim.claims, lim.force), true);
            done++;
        }
        return done;
    }

    /** Тот же шаг ядра, что {@code /rys migrate run claims} и {@code undo claims}: отчёт в лог и сюда. */
    private static int imported(CommandContext<CommandSourceStack> c, boolean undo) throws CommandSyntaxException {
        service();
        MinecraftServer server = c.getSource().getServer();
        Migrations.Outcome o = undo ? Migrations.undo(ClaimsStep.ID, server) : Migrations.runManual(ClaimsStep.ID, server);
        MutableComponent line = Msg.t("import.result", "Land from FTB Chunks: %s %s", o.status().name(), o.message());
        if (o.status() == Migrations.Status.FAILED) return fail(c.getSource(), line);
        c.getSource().sendSuccess(() -> line, true);
        if (o.report() != null) for (String l : o.report().lines()) c.getSource().sendSuccess(() -> Component.literal("  " + l).withStyle(ChatFormatting.GRAY), false);
        return 1;
    }

    /** Строк журнала на страницу {@code /claims log}. */
    private static final int LOG_PAGE = 10;

    /** Журнал отказов команды: кто, что и где пытался сделать на её земле, новые первыми. */
    private static int log(CommandContext<CommandSourceStack> c, UUID team, int page) throws CommandSyntaxException {
        ClaimService s = service();
        CommandSourceStack src = c.getSource();
        DenyLog log = ClaimsServer.log();
        if (log == null) throw NOT_RUNNING.create();
        List<DenyLog.Entry> all = log.recent(team, LOG_PAGE * 100);
        if (all.isEmpty()) {
            src.sendSuccess(() -> Msg.t("log.empty", "Nobody tried anything on the land of %s", teamName(s, team)), false);
            return 0;
        }
        int pages = (all.size() + LOG_PAGE - 1) / LOG_PAGE;
        int p = Math.min(page, pages);
        src.sendSuccess(() -> Msg.t("log.head", "Denied on the land of %s, page %s of %s", teamName(s, team), p, pages), false);
        long now = System.currentTimeMillis();
        for (DenyLog.Entry e : all.subList((p - 1) * LOG_PAGE, Math.min(all.size(), p * LOG_PAGE))) {
            ClaimService.Reason r = ClaimService.Reason.parse(e.reason());
            Component what = r == null ? Component.literal(e.reason()) : Msg.t("log.reason." + r.key(), r.key());
            Component who = e.machine() ? Msg.t("log.machine", "machine %s", e.name()) : Component.literal(e.name());
            MutableComponent line = Msg.t("log.line", "%s ago: %s, %s at %s %s %s", ago(now - e.last()), who, what, e.x(), e.y(), e.z());
            if (e.count() > 1) line.append(Msg.t("log.times", " (%s times)", e.count()));
            if (e.machine() && e.actor() != null) line.append(Component.literal(" " + e.actor()).withStyle(ChatFormatting.DARK_GRAY));
            src.sendSuccess(() -> line, false);
        }
        return all.size();
    }

    /** Сколько прошло: секунды, минуты, часы или дни. */
    private static Component ago(long ms) {
        long sec = Math.max(0, ms / 1000);
        if (sec < 60) return Msg.t("log.sec", "%s s", sec);
        if (sec < 3600) return Msg.t("log.min", "%s min", sec / 60);
        if (sec < 86400) return Msg.t("log.hour", "%s h", sec / 3600);
        return Msg.t("log.day", "%s d", sec / 86400);
    }

    private static int loaded(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        ClaimService s = service();
        Map<UUID, int[]> byTeam = new LinkedHashMap<>();
        for (Map.Entry<String, Long2ObjectMap<UUID>> e : Loader.applied().entrySet()) {
            for (UUID t : e.getValue().values()) byTeam.computeIfAbsent(t, x -> new int[2])[0]++;
        }
        for (TeamLand land : s.data().lands().values()) {
            if (land.forced() > 0) byTeam.computeIfAbsent(land.id, x -> new int[2])[1] = land.forced();
        }
        int total = 0;
        for (int[] v : byTeam.values()) total += v[0];
        int t = total;
        c.getSource().sendSuccess(() -> Msg.t("loaded.head", "Force loaded by land: %s chunks", t), false);
        for (Map.Entry<UUID, int[]> e : byTeam.entrySet()) {
            int[] v = e.getValue();
            c.getSource().sendSuccess(() -> Msg.t("loaded.team", "  %s: working %s of %s switched on", teamName(s, e.getKey()), v[0], v[1])
                .withStyle(ChatFormatting.GRAY), false);
        }
        return total;
    }
}

package works.repka.rysclaims.dev;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.entity.monster.EnderMan;
import net.minecraft.world.entity.projectile.Arrow;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import works.repka.rysclaims.RYSClaims;
import works.repka.rysclaims.data.Chunks;
import works.repka.rysclaims.data.Rights;
import works.repka.rysclaims.data.TeamLand;
import works.repka.rysclaims.server.ClaimService;
import works.repka.rysclaims.server.ClaimsServer;
import works.repka.rysclaims.server.DenyLog;
import works.repka.rysclaims.server.Loader;

/**
 * З2, часть 2, на тестовом сервере: вода и огонь через границу, снаряд игрока по блоку, журнал отказов, перехваты
 * эндермена и молнии загружаются. Каждый случай в двух видах: через границу на чужую землю нельзя, по своей земле можно.
 */
@GameTestHolder(RYSClaims.MODID)
@PrefixGameTestTemplate(false)
public final class BordersGameTests {
    private BordersGameTests() {}

    /** Свой квадрат мира для каждого теста, далеко от ProtectionGameTests. */
    private static BlockPos base(int slot) {
        return new BlockPos(-30000 - slot * 256, 100, 20000);
    }

    private static long chunk(BlockPos p) {
        return Chunks.pack(p.getX() >> 4, p.getZ() >> 4);
    }

    private static void claim(ServerLevel level, UUID team, BlockPos... positions) {
        LongArrayList list = new LongArrayList();
        for (BlockPos p : positions) list.add(chunk(p));
        ClaimsServer.service().claim(null, team, Loader.id(level.dimension()), list, true);
    }

    private static void free(ServerLevel level, BlockPos... positions) {
        ClaimService s = ClaimsServer.service();
        String dim = Loader.id(level.dimension());
        for (BlockPos p : positions) if (s.data().get(dim, chunk(p)) != null) s.data().remove(dim, chunk(p));
    }

    /**
     * Билет на чанки теста. Не пользоваться без нужды: билет в далёкой несгенерированной земле запускает генерацию сотен
     * чанков вокруг, и остановка сервера GameTest ждёт её минутами. Тесты тикают сами (вызов tick, triggerEvent).
     */
    static void force(ServerLevel level, boolean on, BlockPos... positions) {
        for (BlockPos p : positions) level.setChunkForced(p.getX() >> 4, p.getZ() >> 4, on);
    }

    private static void floor(ServerLevel level, BlockPos from, BlockPos to) {
        for (BlockPos p : BlockPos.betweenClosed(from, to)) level.setBlock(p, Blocks.STONE.defaultBlockState(), 3);
    }

    /**
     * По умолчанию (автор 20.11) вода течёт через границу земли. С закрытой границей ({@code fluidsAcrossBorders = false})
     * вода со свободной земли не течёт на землю команды; по своей земле через границу чанков течёт. Спрашиваем ту же
     * {@code FlowingFluid.canSpreadTo}, что зовёт растекание: так проверяется сам перехват, без растекания в далёких
     * чанках (вода в нетикающем чанке подвешивает остановку сервера GameTest).
     */
    @GameTest(template = "empty", timeoutTicks = 100)
    public static void waterAcrossBorder(GameTestHelper h) throws ReflectiveOperationException {
        ServerLevel level = h.getLevel();
        BlockPos corner = base(0);
        BlockPos source = corner.offset(15, 1, 8); // последний столбец свободного чанка
        BlockPos foreign = corner.offset(16, 1, 8); // первый столбец чанка команды
        BlockPos ownEdge = corner.offset(31, 1, 8); // своя вода у границы своих чанков
        BlockPos ownNext = corner.offset(32, 1, 8);
        UUID team = UUID.randomUUID();
        claim(level, team, foreign, ownNext);
        try {
            var m = net.minecraft.world.level.material.FlowingFluid.class.getDeclaredMethod("canSpreadTo",
                net.minecraft.world.level.BlockGetter.class, BlockPos.class, BlockState.class, net.minecraft.core.Direction.class,
                BlockPos.class, BlockState.class, net.minecraft.world.level.material.FluidState.class, net.minecraft.world.level.material.Fluid.class);
            m.setAccessible(true);
            var water = (net.minecraft.world.level.material.FlowingFluid) net.minecraft.world.level.material.Fluids.WATER;
            BlockState w = Blocks.WATER.defaultBlockState(), air = Blocks.AIR.defaultBlockState();
            var empty = net.minecraft.world.level.material.Fluids.EMPTY.defaultFluidState();
            String was = works.repka.rysclaims.server.ServerConfig.values().getOrDefault("fluidsAcrossBorders", "true");
            works.repka.rysclaims.server.ServerConfig.set("fluidsAcrossBorders", "true");
            boolean openBorder = (boolean) m.invoke(water, level, source, w, net.minecraft.core.Direction.EAST, foreign, air, empty, water.getFlowing());
            works.repka.rysclaims.server.ServerConfig.set("fluidsAcrossBorders", "false");
            boolean intoForeign = (boolean) m.invoke(water, level, source, w, net.minecraft.core.Direction.EAST, foreign, air, empty, water.getFlowing());
            works.repka.rysclaims.server.ServerConfig.set("fluidsAcrossBorders", was);
            if (!openBorder) h.fail("граница открыта, а вода на землю команды не течёт");
            boolean inWild = (boolean) m.invoke(water, level, source, w, net.minecraft.core.Direction.WEST, source.west(), air, empty, water.getFlowing());
            boolean ownBorder = (boolean) m.invoke(water, level, ownEdge, w, net.minecraft.core.Direction.EAST, ownNext, air, empty, water.getFlowing());
            if (!inWild) h.fail("вода не течёт даже по свободной земле: тест ничего не проверяет");
            if (intoForeign) h.fail("вода со свободной земли затекает на землю команды");
            if (!ownBorder) h.fail("вода не перетекает между своими чанками");
        } finally {
            free(level, foreign, ownNext);
        }
        done(h);
    }

    /** Огонь у границы жжёт свою доску и не трогает доску на земле другой команды. */
    @GameTest(template = "empty", timeoutTicks = 100)
    public static void fireAcrossBorder(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        BlockPos corner = base(1);
        BlockPos fire = corner.offset(15, 1, 8);
        BlockPos own = fire.west(); // тот же свободный чанк
        BlockPos foreign = fire.east(); // чанк команды
        UUID team = UUID.randomUUID();
        claim(level, team, foreign);
        var fireTick = level.getGameRules().getRule(net.minecraft.world.level.GameRules.RULE_DOFIRETICK);
        boolean wasFireTick = fireTick.get();
        fireTick.set(true, level.getServer()); // сервер GameTest выключает огонь
        try {
            floor(level, corner.offset(12, 0, 6), corner.offset(18, 0, 10));
            level.setBlock(own, Blocks.OAK_PLANKS.defaultBlockState(), 3);
            level.setBlock(foreign, Blocks.OAK_PLANKS.defaultBlockState(), 3);
            for (int i = 0; i < 600 && level.getBlockState(own).is(Blocks.OAK_PLANKS); i++) {
                BlockState f = level.getBlockState(fire);
                if (!f.is(Blocks.FIRE)) level.setBlock(fire, Blocks.FIRE.defaultBlockState(), 3);
                level.getBlockState(fire).tick(level, fire, level.random);
            }
            for (int i = 0; i < 600; i++) {
                if (!level.getBlockState(fire).is(Blocks.FIRE)) level.setBlock(fire, Blocks.FIRE.defaultBlockState(), 3);
                level.getBlockState(fire).tick(level, fire, level.random);
            }
            if (level.getBlockState(own).is(Blocks.OAK_PLANKS)) h.fail("огонь за 600 тиков не тронул доску на своей стороне");
            if (!level.getBlockState(foreign).is(Blocks.OAK_PLANKS)) h.fail("огонь перешёл на землю другой команды");
        } finally {
            fireTick.set(wasFireTick, level.getServer());
            free(level, foreign);
        }
        done(h);
    }

    /** Стрела игрока по блоку: на чужой земле mayInteract отказывает (ТНТ, костёр, хорус), хозяину можно. */
    @GameTest(template = "empty", timeoutTicks = 100)
    public static void projectileOwner(GameTestHelper h) {
        if (CompatRuns.skipMockPlayers(h, "projectileOwner")) return;
        ServerLevel level = h.getLevel();
        BlockPos pos = base(2).offset(8, 1, 8);
        ServerPlayer stranger = h.makeMockServerPlayerInLevel();
        ServerPlayer owner = h.makeMockServerPlayerInLevel();
        claim(level, owner.getUUID(), pos);
        try {
            Arrow arrow = new Arrow(EntityType.ARROW, level);
            arrow.setOwner(stranger);
            if (arrow.mayInteract(level, pos)) h.fail("стрела чужого может трогать блок на земле команды");
            arrow.setOwner(owner);
            if (!arrow.mayInteract(level, pos)) h.fail("стрела хозяина не может трогать блок своей земли");
        } finally {
            level.getServer().getPlayerList().remove(stranger);
            level.getServer().getPlayerList().remove(owner);
            free(level, pos);
        }
        done(h);
    }

    /** Отказ уходит в журнал хозяев с причиной и склеивается при повторе. */
    @GameTest(template = "empty", timeoutTicks = 100)
    public static void denyIsLogged(GameTestHelper h) {
        if (CompatRuns.skipMockPlayers(h, "denyIsLogged")) return;
        ServerLevel level = h.getLevel();
        BlockPos pos = base(3).offset(8, 1, 8);
        UUID team = UUID.randomUUID();
        claim(level, team, pos);
        ServerPlayer stranger = h.makeMockServerPlayerInLevel();
        try {
            level.setBlock(pos, Blocks.STONE.defaultBlockState(), 3);
            stranger.gameMode.destroyBlock(pos);
            stranger.gameMode.destroyBlock(pos);
            DenyLog log = ClaimsServer.log();
            if (log == null) {
                h.fail("журнала нет");
                return;
            }
            var entries = log.pending(team);
            if (entries.size() != 1) h.fail("в журнале " + entries.size() + " записей вместо одной склеенной");
            DenyLog.Entry e = entries.get(0);
            if (!"build".equals(e.reason()) || e.count() < 2 || !stranger.getUUID().equals(e.actor())) h.fail("запись журнала не та: " + e);
            // доверенный строит, журнал не растёт
            TeamLand land = ClaimsServer.service().data().landOrCreate(team);
            land.trusted.add(stranger.getUUID());
            land.rights.set(Rights.Action.BUILD, Rights.ALLIES, true);
            int before = e.count();
            stranger.gameMode.destroyBlock(pos);
            if (log.pending(team).get(0).count() != before) h.fail("разрешённое действие попало в журнал");
        } finally {
            level.getServer().getPlayerList().remove(stranger);
            free(level, pos);
        }
        done(h);
    }

    /**
     * Молния игрока («Громовержец») на чужой земле огня не ставит; эндермен создаётся (перехват его цели загружается,
     * {@code defaultRequire = 1} уронил бы сервер, если цель перехвата не найдена).
     */
    @GameTest(template = "empty", timeoutTicks = 100)
    public static void lightningAndEnderman(GameTestHelper h) {
        if (CompatRuns.skipMockPlayers(h, "lightningAndEnderman")) return;
        ServerLevel level = h.getLevel();
        BlockPos pos = base(4).offset(8, 1, 8);
        UUID team = UUID.randomUUID();
        claim(level, team, pos);
        level.getServer().setDifficulty(net.minecraft.world.Difficulty.NORMAL, true);
        var fireTick = level.getGameRules().getRule(net.minecraft.world.level.GameRules.RULE_DOFIRETICK);
        fireTick.set(true, level.getServer());
        ServerPlayer stranger = h.makeMockServerPlayerInLevel();
        floor(level, pos.offset(-2, -1, -2), pos.offset(2, -1, 2));
        EnderMan enderman = EntityType.ENDERMAN.create(level);
        if (enderman == null) h.fail("эндермен не создан");
        LightningBolt bolt = EntityType.LIGHTNING_BOLT.create(level);
        if (bolt == null) {
            h.fail("молния не создана");
            return;
        }
        bolt.moveTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
        bolt.setCause(stranger);
        // тикаем молнию сами: чанк теста не тикает, а билет на него запустил бы генерацию вокруг
        for (int i = 0; i < 4; i++) bolt.tick();
        h.runAfterDelay(3, () -> {
            boolean fire = false;
            for (BlockPos p : BlockPos.betweenClosed(pos.offset(-1, -1, -1), pos.offset(1, 1, 1))) {
                if (level.getBlockState(p).is(Blocks.FIRE)) fire = true;
            }
            level.getServer().getPlayerList().remove(stranger);
            free(level, pos);
            fireTick.set(false, level.getServer());
            if (fire) h.fail("молния чужого игрока подожгла землю команды");
            h.succeed();
        });
    }

    /**
     * Успех через несколько тиков: синхронная загрузка чанка ставит временный билет, и если тест кончился в том же тике,
     * остановка сервера GameTest крутится в выгрузке чанков бесконечно.
     */
    static void done(GameTestHelper h) {
        h.runAfterDelay(3, h::succeed);
    }
}

package works.repka.rysclaims.dev;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DispenserBlock;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.SaplingBlock;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.DispenserBlockEntity;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import works.repka.rysclaims.RYSClaims;
import works.repka.rysclaims.data.Chunks;
import works.repka.rysclaims.server.Borders;
import works.repka.rysclaims.server.ClaimService;
import works.repka.rysclaims.server.ClaimsServer;
import works.repka.rysclaims.server.Loader;

/**
 * З9 на тестовом сервере: воронка, выбрасыватель, раздатчик, падающий блок, плита, рост дерева через границу; перехват
 * скалка загружается. Каждый случай в двух видах: на землю другой команды нельзя, по своей земле можно.
 */
@GameTestHolder(RYSClaims.MODID)
@PrefixGameTestTemplate(false)
public final class CrossGameTests {
    private CrossGameTests() {}

    private static BlockPos base(int slot) {
        return new BlockPos(-30000 - slot * 256, 100, -20000);
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

    private static boolean chestEmpty(ServerLevel level, BlockPos pos) {
        return level.getBlockEntity(pos) instanceof ChestBlockEntity c && c.isEmpty();
    }

    /** Воронка у границы не кладёт в сундук на земле другой команды; со свободной земли и со своей земли кладёт. */
    @GameTest(template = "empty", timeoutTicks = 100)
    public static void hopperAcrossBorder(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        BlockPos corner = base(0);
        BlockPos hopper = corner.offset(15, 1, 8), chest = hopper.east();
        UUID team = UUID.randomUUID();
        claim(level, team, chest);
        try {
            level.setBlock(chest, Blocks.CHEST.defaultBlockState(), 3);
            BlockState hs = Blocks.HOPPER.defaultBlockState().setValue(HopperBlock.FACING, Direction.EAST);
            level.setBlock(hopper, hs, 3);
            HopperBlockEntity be = (HopperBlockEntity) level.getBlockEntity(hopper);
            be.setItem(0, new ItemStack(Items.COBBLESTONE, 4));
            claim(level, UUID.randomUUID(), hopper);
            HopperBlockEntity.pushItemsTick(level, hopper, hs, be);
            if (!chestEmpty(level, chest)) h.fail("воронка с земли другой команды положила в сундук команды");
            free(level, hopper);
            be.setCooldown(0);
            HopperBlockEntity.pushItemsTick(level, hopper, hs, be);
            if (chestEmpty(level, chest)) h.fail("воронка со свободной земли не кладёт в сундук команды");
            claim(level, team, hopper);
            be.setCooldown(0);
            HopperBlockEntity.pushItemsTick(level, hopper, hs, be);
            if (be.getItem(0).getCount() > 2) h.fail("воронка на своей земле не кладёт в свой сундук через границу чанков");
        } finally {
            level.removeBlock(hopper, false);
            level.removeBlock(chest, false);
            free(level, hopper, chest);
        }
        BordersGameTests.done(h);
    }

    /**
     * Снаряд без хозяина (раздатчик) судится чанком, где появился (перепроверка 18.11, пункт 4): огненный заряд со свободной
     * земли по блоку команды не попадает и пропадает, по своей земле попадает.
     */
    @GameTest(template = "empty", timeoutTicks = 100)
    public static void ownerlessProjectile(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        BlockPos corner = base(5);
        BlockPos from = corner.offset(15, 2, 8), target = corner.offset(16, 1, 8);
        UUID team = UUID.randomUUID();
        claim(level, team, target);
        try {
            net.minecraft.world.entity.projectile.SmallFireball ball = new net.minecraft.world.entity.projectile.SmallFireball(
                net.minecraft.world.entity.EntityType.SMALL_FIREBALL, level);
            ball.setPos(from.getX() + 0.5, from.getY() + 0.5, from.getZ() + 0.5);
            net.neoforged.neoforge.common.NeoForge.EVENT_BUS.post(new net.neoforged.neoforge.event.entity.EntityJoinLevelEvent(ball, level));
            var hit = new net.minecraft.world.phys.BlockHitResult(net.minecraft.world.phys.Vec3.atCenterOf(target), Direction.UP, target, false);
            if (!net.neoforged.neoforge.event.EventHooks.onProjectileImpact(ball, hit)) h.fail("заряд со свободной земли попал в блок команды");
            if (!ball.isRemoved()) h.fail("снаряд без хозяина не пропал после отказа");

            claim(level, team, from);
            net.minecraft.world.entity.projectile.SmallFireball own = new net.minecraft.world.entity.projectile.SmallFireball(
                net.minecraft.world.entity.EntityType.SMALL_FIREBALL, level);
            own.setPos(from.getX() + 0.5, from.getY() + 0.5, from.getZ() + 0.5);
            net.neoforged.neoforge.common.NeoForge.EVENT_BUS.post(new net.neoforged.neoforge.event.entity.EntityJoinLevelEvent(own, level));
            if (net.neoforged.neoforge.event.EventHooks.onProjectileImpact(own, hit)) h.fail("заряд со своей земли не попадает в свой блок");
        } finally {
            free(level, from, target);
        }
        BordersGameTests.done(h);
    }

    /** Выбрасыватель не кладёт в сундук за границей; раздатчик не ставит шалкер на землю другой команды. */
    @GameTest(template = "empty", timeoutTicks = 100)
    public static void dispenserAcrossBorder(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        BlockPos corner = base(1);
        BlockPos dropper = corner.offset(15, 1, 4), chest = dropper.east();
        BlockPos dispenser = corner.offset(15, 1, 10), target = dispenser.east();
        UUID team = UUID.randomUUID();
        claim(level, team, chest);
        try {
            level.setBlock(chest, Blocks.CHEST.defaultBlockState(), 3);
            BlockState ds = Blocks.DROPPER.defaultBlockState().setValue(DispenserBlock.FACING, Direction.EAST);
            level.setBlock(dropper, ds, 3);
            ((DispenserBlockEntity) level.getBlockEntity(dropper)).setItem(0, new ItemStack(Items.COBBLESTONE, 4));
            claim(level, UUID.randomUUID(), dropper);
            ds.tick(level, dropper, level.random);
            if (!chestEmpty(level, chest)) h.fail("выбрасыватель с земли другой команды положил в сундук команды");
            free(level, dropper);
            ds.tick(level, dropper, level.random);
            if (chestEmpty(level, chest)) h.fail("выбрасыватель со свободной земли не кладёт в сундук команды");

            BlockState ps = Blocks.DISPENSER.defaultBlockState().setValue(DispenserBlock.FACING, Direction.EAST);
            level.setBlock(dispenser, ps, 3);
            ((DispenserBlockEntity) level.getBlockEntity(dispenser)).setItem(0, new ItemStack(Items.SHULKER_BOX));
            ps.tick(level, dispenser, level.random);
            if (level.getBlockState(target).is(Blocks.SHULKER_BOX)) h.fail("раздатчик со свободной земли поставил блок на землю команды");

            claim(level, team, dropper, dispenser);
            int before = ((DispenserBlockEntity) level.getBlockEntity(dropper)).getItem(0).getCount();
            ds.tick(level, dropper, level.random);
            if (((DispenserBlockEntity) level.getBlockEntity(dropper)).getItem(0).getCount() >= before) h.fail("выбрасыватель на своей земле не кладёт в свой сундук");
            ps.tick(level, dispenser, level.random);
            if (!level.getBlockState(target).is(Blocks.SHULKER_BOX)) h.fail("раздатчик на своей земле не ставит блок рядом");
        } finally {
            for (BlockPos p : new BlockPos[] {dropper, chest, dispenser, target}) level.removeBlock(p, false);
            free(level, chest, target);
        }
        BordersGameTests.done(h);
    }

    /** Песок, начавший падение на свободной земле, на землю команды не ложится: выпадает предметом. */
    @GameTest(template = "empty", timeoutTicks = 100)
    public static void fallingAcrossBorder(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        BlockPos corner = base(2);
        BlockPos start = corner.offset(15, 4, 8), land = corner.offset(16, 1, 8);
        UUID team = UUID.randomUUID();
        claim(level, team, land);
        AABB box = new AABB(land).inflate(3);
        try {
            level.setBlock(land.below(), Blocks.STONE.defaultBlockState(), 3);
            level.setBlock(start, Blocks.SAND.defaultBlockState(), 3);
            FallingBlockEntity sand = FallingBlockEntity.fall(level, start, Blocks.SAND.defaultBlockState());
            sand.setPos(land.getX() + 0.5, land.getY() + 2, land.getZ() + 0.5);
            for (int i = 0; i < 60 && !sand.isRemoved(); i++) sand.tick();
            if (level.getBlockState(land).is(Blocks.SAND)) h.fail("песок со свободной земли лёг на землю команды");
            if (level.getEntitiesOfClass(ItemEntity.class, box).isEmpty()) h.fail("песок не выпал предметом");
            if (!Borders.falling(level, land, land.east())) h.fail("падающий блок не ложится внутри своей земли");
        } finally {
            level.getEntitiesOfClass(ItemEntity.class, box).forEach(e -> e.discard());
            level.removeBlock(land, false);
            level.removeBlock(land.below(), false);
            free(level, land);
        }
        BordersGameTests.done(h);
    }

    /** Плита на земле команды не чувствует чужого игрока и чувствует хозяина. */
    @GameTest(template = "empty", timeoutTicks = 100)
    public static void plateUnderStranger(GameTestHelper h) throws ReflectiveOperationException {
        if (CompatRuns.skipMockPlayers(h, "plateUnderStranger")) return;
        ServerLevel level = h.getLevel();
        BlockPos plate = base(3).offset(8, 1, 8);
        ServerPlayer owner = h.makeMockServerPlayerInLevel();
        ServerPlayer stranger = h.makeMockServerPlayerInLevel();
        claim(level, ClaimsServer.service().teams().teamOf(owner.getUUID()), plate);
        try {
            level.setBlock(plate.below(), Blocks.STONE.defaultBlockState(), 3);
            level.setBlock(plate, Blocks.STONE_PRESSURE_PLATE.defaultBlockState(), 3);
            var m = net.minecraft.world.level.block.PressurePlateBlock.class.getDeclaredMethod("getSignalStrength",
                net.minecraft.world.level.Level.class, BlockPos.class);
            m.setAccessible(true);
            owner.setPos(plate.getX() + 50, plate.getY(), plate.getZ());
            stranger.setPos(plate.getX() + 0.5, plate.getY(), plate.getZ() + 0.5);
            int s1 = (int) m.invoke(Blocks.STONE_PRESSURE_PLATE, level, plate);
            stranger.setPos(plate.getX() + 50, plate.getY(), plate.getZ());
            owner.setPos(plate.getX() + 0.5, plate.getY(), plate.getZ() + 0.5);
            int s2 = (int) m.invoke(Blocks.STONE_PRESSURE_PLATE, level, plate);
            if (s1 != 0) h.fail("плита команды нажата чужим игроком");
            if (s2 == 0) h.fail("плита команды не чувствует хозяина");
        } finally {
            level.removeBlock(plate, false);
            level.removeBlock(plate.below(), false);
            free(level, plate);
            owner.discard();
            stranger.discard();
        }
        BordersGameTests.done(h);
    }

    /** Дуб у самой границы не растёт, если рядом земля другой команды; вдали от чужой земли растёт. Скалк загружается. */
    @GameTest(template = "empty", timeoutTicks = 100)
    public static void treeNearForeignLand(GameTestHelper h) throws ClassNotFoundException {
        ServerLevel level = h.getLevel();
        BlockPos corner = base(4);
        BlockPos sapling = corner.offset(13, 1, 8), foreign = corner.offset(17, 1, 8), far = corner.offset(80, 1, 8);
        UUID team = UUID.randomUUID();
        claim(level, team, foreign);
        try {
            for (BlockPos p : new BlockPos[] {sapling, far}) {
                level.setBlock(p.below(), Blocks.DIRT.defaultBlockState(), 3);
                level.setBlock(p, Blocks.OAK_SAPLING.defaultBlockState().setValue(SaplingBlock.STAGE, 1), 3);
            }
            ((SaplingBlock) Blocks.OAK_SAPLING).advanceTree(level, sapling, level.getBlockState(sapling), level.random);
            if (!level.getBlockState(sapling).is(Blocks.OAK_SAPLING)) h.fail("дерево выросло у самой земли другой команды");
            boolean grew = false;
            for (int i = 0; i < 20 && !grew; i++) {
                ((SaplingBlock) Blocks.OAK_SAPLING).advanceTree(level, far, level.getBlockState(far).setValue(SaplingBlock.STAGE, 1), level.random);
                grew = !level.getBlockState(far).is(Blocks.OAK_SAPLING);
            }
            if (!grew) h.fail("дерево вдали от чужой земли не растёт: тест ничего не проверяет");
            Class.forName("net.minecraft.world.level.block.SculkSpreader$ChargeCursor");
            if (Borders.sculk(level, foreign.west(5), foreign)) h.fail("скалк со свободной земли переходит на землю команды");
        } finally {
            free(level, foreign);
        }
        BordersGameTests.done(h);
    }
}

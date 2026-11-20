package works.repka.rysclaims.dev;

import com.mojang.authlib.GameProfile;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import java.lang.reflect.Method;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import works.repka.rysclaims.RYSClaims;
import works.repka.rysclaims.data.Chunks;
import works.repka.rysclaims.server.ClaimService;
import works.repka.rysclaims.server.ClaimsServer;
import works.repka.rysclaims.server.DenyLog;
import works.repka.rysclaims.server.Loader;
import works.repka.rysclaims.server.Machines;

/**
 * Машины мимо событий (R0, разделы 0 и 2) на тестовом сервере: {@code ServerLevel.mayInteract} и {@code BlockItem.placeBlock}
 * для фейковых игроков, правило места для блок-сущностей, ломатель Cyclic (если мод подложен: {@code -PcompatMods=}).
 */
@GameTestHolder(RYSClaims.MODID)
@PrefixGameTestTemplate(false)
public final class MachinesGameTests {
    private MachinesGameTests() {}

    private static BlockPos base(int slot) {
        return new BlockPos(20000, 100, -30000 - slot * 256);
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

    /** Плоскость разрушения AE2 и подобные спрашивают mayInteract: чужой профиль нельзя, профиль хозяина можно, живой игрок как раньше. */
    @GameTest(template = "empty", timeoutTicks = 100)
    public static void mayInteractForMachines(GameTestHelper h) {
        if (CompatRuns.skipMockPlayers(h, "mayInteractForMachines")) return;
        ServerLevel level = h.getLevel();
        BlockPos pos = base(0).offset(8, 1, 8);
        UUID owner = UUID.randomUUID();
        claim(level, owner, pos);
        ServerPlayer stranger = h.makeMockServerPlayerInLevel();
        try {
            FakePlayer shared = FakePlayerFactory.get(level, new GameProfile(UUID.randomUUID(), "[AE2]"));
            FakePlayer mine = FakePlayerFactory.get(level, new GameProfile(owner, "[AE2]"));
            if (level.mayInteract(shared, pos)) h.fail("машина с чужим профилем прошла mayInteract на земле команды");
            if (!level.mayInteract(mine, pos)) h.fail("машина с профилем хозяина не прошла mayInteract");
            if (!level.mayInteract(stranger, pos)) h.fail("живому игроку mayInteract закрыт: закрылись бы двери и верстаки");
            DenyLog log = ClaimsServer.log();
            if (log == null || log.pending(owner).stream().noneMatch(e -> e.machine() && "[AE2]".equals(e.name()))) {
                h.fail("отказ машине не попал в журнал с её именем");
            }
        } finally {
            level.getServer().getPlayerList().remove(stranger);
            free(level, pos);
        }
        done(h);
    }

    /** Плоскость формирования AE2 ставит через Item.useOn мимо EntityPlaceEvent: перехват в BlockItem.placeBlock. */
    @GameTest(template = "empty", timeoutTicks = 100)
    public static void placeBlockForMachines(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        BlockPos floor = base(1).offset(8, 1, 8);
        UUID owner = UUID.randomUUID();
        BlockPos target = floor.above();
        claim(level, owner, floor);
        try {
            // место вне площадки теста живёт в мире между запусками: доска прошлого прогона не должна сойти за установку
            level.setBlock(floor, Blocks.STONE.defaultBlockState(), 3);
            level.setBlock(target, Blocks.AIR.defaultBlockState(), 3);
            FakePlayer shared = FakePlayerFactory.get(level, new GameProfile(UUID.randomUUID(), "[Formation]"));
            place(level, shared, floor);
            if (!level.getBlockState(target).isAir()) h.fail("машина с чужим профилем поставила блок мимо события");
            FakePlayer mine = FakePlayerFactory.get(level, new GameProfile(owner, "[Formation]"));
            place(level, mine, floor);
            if (!level.getBlockState(target).is(Blocks.OAK_PLANKS)) h.fail("машина с профилем хозяина не поставила блок");
        } finally {
            level.setBlock(target, Blocks.AIR.defaultBlockState(), 3);
            level.setBlock(floor, Blocks.AIR.defaultBlockState(), 3);
            free(level, floor);
        }
        done(h);
    }

    /**
     * Фейк с общим профилем (Actually Additions, {@code [Minecraft]}) внутри тика блок-сущности (разбор фейков 20.11): своя
     * земля открыта, чужая через границу нет, без тика место неизвестно. Тик здесь ставится руками, перехват тика тот же
     * {@link Machines#ticking(BlockEntity)}.
     */
    @GameTest(template = "empty", timeoutTicks = 100)
    public static void sharedFakeUsesTickingMachine(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        BlockPos machine = base(9).offset(8, 1, 8);
        BlockPos ownNext = machine.offset(16, 0, 0), foreign = machine.offset(32, 0, 0);
        UUID team = UUID.randomUUID(), other = UUID.randomUUID();
        claim(level, team, machine, ownNext);
        claim(level, other, foreign);
        try {
            level.setBlock(machine, Blocks.FURNACE.defaultBlockState(), 3);
            BlockEntity be = level.getBlockEntity(machine);
            if (be == null) h.fail("нет блок-сущности печи");
            // печь с тикером загрузила класс обёртки тика: перехват mixin/BlockEntityTickMixin обязан в нём стоять
            if (!tickHooked()) h.fail("перехват тика блок-сущности (BlockEntityTickMixin) не встал");
            FakePlayer fake = FakePlayerFactory.getMinecraft(level);
            if (level.mayInteract(fake, ownNext)) h.fail("без тика машины общий фейк прошёл на землю команды");
            BlockEntity prev = Machines.ticking(be);
            try {
                if (!level.mayInteract(fake, ownNext)) h.fail("машина на своей земле не достала соседний свой чанк");
                if (level.mayInteract(fake, foreign)) h.fail("машина достала чужую землю через границу");
            } finally {
                Machines.ticking(prev);
            }
            if (Machines.ticking() != null) h.fail("тикающая машина не снята");
            DenyLog log = ClaimsServer.log();
            if (log == null || log.pending(other).stream().noneMatch(e -> e.machine() && e.name().contains("minecraft:furnace"))) {
                h.fail("отказ через границу не попал в журнал соседа с именем машины");
            }
        } finally {
            level.setBlock(machine, Blocks.AIR.defaultBlockState(), 3);
            free(level, machine, ownNext, foreign);
        }
        done(h);
    }

    /** Обработчик {@code rysclaims$machine} влит в {@code LevelChunk$BoundTickingBlockEntity} (Mixin переименовывает его с приставкой). */
    private static boolean tickHooked() {
        try {
            for (Method m : Class.forName("net.minecraft.world.level.chunk.LevelChunk$BoundTickingBlockEntity").getDeclaredMethods()) {
                if (m.getName().contains("rysclaims$machine")) return true;
            }
            return false;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    private static void place(ServerLevel level, FakePlayer player, BlockPos floor) {
        ItemStack stack = new ItemStack(Items.OAK_PLANKS);
        player.setItemInHand(InteractionHand.MAIN_HAND, stack);
        UseOnContext ctx = new UseOnContext(level, player, InteractionHand.MAIN_HAND, stack,
            new BlockHitResult(Vec3.atCenterOf(floor).add(0, 0.5, 0), Direction.UP, floor, false));
        stack.getItem().useOn(ctx); // как AE2: Item.useOn, а не ItemStack.useOn
    }

    /** Блок-сущность: на своей земле достаёт свою далёкую землю, через границу нет. */
    @GameTest(template = "empty", timeoutTicks = 100)
    public static void blockEntityPlace(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        BlockPos machine = base(2).offset(8, 1, 8);
        BlockPos ownFar = machine.offset(16 * 6, 0, 0);
        BlockPos foreign = machine.offset(16, 0, 0);
        UUID team = UUID.randomUUID();
        claim(level, team, machine, ownFar);
        claim(level, UUID.randomUUID(), foreign);
        try {
            level.setBlock(machine, Blocks.CHEST.defaultBlockState(), 3);
            BlockEntity be = level.getBlockEntity(machine);
            if (be == null) {
                h.fail("нет блок-сущности сундука");
                return;
            }
            if (!Machines.blockEntity(be, level, ownFar)) h.fail("машина не достала свою землю в 6 чанках");
            if (Machines.blockEntity(be, level, foreign)) h.fail("машина достала землю другой команды");
            if (!Machines.blockEntity(be, level, machine.offset(-40, 0, 0))) h.fail("машина не достала свободную землю");
        } finally {
            free(level, machine, ownFar, foreign);
        }
        done(h);
    }

    /** Ломатель Cyclic у границы: чужой камень цел, свободный сломан. Без Cyclic тест проходит пустым. */
    @GameTest(template = "empty", timeoutTicks = 100)
    public static void cyclicBreaker(GameTestHelper h) throws ReflectiveOperationException {
        if (!ModList.get().isLoaded("cyclic")) {
            RYSClaims.LOG.info("[rysclaims-test] cyclicBreaker: Cyclic не подложен, перехват не проверен");
            h.succeed();
            return;
        }
        ServerLevel level = h.getLevel();
        Block breaker = BuiltInRegistries.BLOCK.get(ResourceLocation.fromNamespaceAndPath("cyclic", "breaker"));
        BlockPos corner = base(3);
        BlockPos atBorder = corner.offset(15, 1, 8); // свободный чанк, последний столбец
        BlockPos foreign = atBorder.east();
        BlockPos wildMachine = corner.offset(5, 1, 8);
        BlockPos wild = wildMachine.east();
        UUID team = UUID.randomUUID();
        claim(level, team, foreign);
        try {
            boolean foreignKept = run(level, breaker, atBorder, foreign);
            boolean wildBroken = !run(level, breaker, wildMachine, wild);
            if (!wildBroken) h.fail("ломатель Cyclic не ломает и на свободной земле: тест ничего не проверяет");
            if (!foreignKept) h.fail("ломатель Cyclic сломал камень на земле другой команды");
        } finally {
            free(level, foreign);
        }
        done(h);
    }

    /**
     * Create (если подложен): бур у границы не берётся за камень другой команды, на свободной земле берётся
     * ({@code canBreak} неподвижного бура); постройка несёт место якоря (перехват {@code Contraption} встал).
     */
    @GameTest(template = "empty", timeoutTicks = 100)
    public static void createDrill(GameTestHelper h) throws ReflectiveOperationException {
        if (!ModList.get().isLoaded("create")) {
            RYSClaims.LOG.info("[rysclaims-test] createDrill: Create не подложен, перехват не проверен");
            h.succeed();
            return;
        }
        Class<?> contraption = Class.forName("com.simibubi.create.content.contraptions.Contraption");
        if (!works.repka.rysclaims.compat.create.ContraptionPlace.class.isAssignableFrom(contraption)) {
            h.fail("перехват Contraption не встал");
            return;
        }
        ServerLevel level = h.getLevel();
        Block drill = BuiltInRegistries.BLOCK.get(ResourceLocation.fromNamespaceAndPath("create", "mechanical_drill"));
        BlockPos corner = base(4);
        BlockPos atBorder = corner.offset(15, 1, 8);
        BlockPos foreign = atBorder.east();
        BlockPos wildMachine = corner.offset(5, 1, 8);
        UUID team = UUID.randomUUID();
        claim(level, team, foreign);
        try {
            boolean foreignOk = canBreak(level, drill, atBorder, foreign);
            boolean wildOk = canBreak(level, drill, wildMachine, wildMachine.east());
            if (!wildOk) h.fail("бур Create не берётся за камень и на свободной земле: тест ничего не проверяет");
            if (foreignOk) h.fail("бур Create берётся за камень на земле другой команды");
        } finally {
            free(level, foreign);
        }
        done(h);
    }

    private static boolean canBreak(ServerLevel level, Block drill, BlockPos at, BlockPos target) throws ReflectiveOperationException {
        BlockState state = drill.defaultBlockState();
        if (state.hasProperty(BlockStateProperties.FACING)) state = state.setValue(BlockStateProperties.FACING, Direction.EAST);
        level.setBlock(at, state, 3);
        level.setBlock(target, Blocks.STONE.defaultBlockState(), 3);
        BlockEntity be = level.getBlockEntity(at);
        Class<?> base = Class.forName("com.simibubi.create.content.kinetics.base.BlockBreakingKineticBlockEntity");
        java.lang.reflect.Field pos = base.getDeclaredField("breakingPos");
        pos.setAccessible(true);
        pos.set(be, target);
        Method can = base.getMethod("canBreak", BlockState.class, float.class);
        return (Boolean) can.invoke(be, Blocks.STONE.defaultBlockState(), 1.5f);
    }

    /** Ставит ломатель лицом на восток, питание, камень перед ним; тикает; отвечает, цел ли камень. */
    private static boolean run(ServerLevel level, Block breaker, BlockPos at, BlockPos target) throws ReflectiveOperationException {
        BlockState state = breaker.defaultBlockState();
        if (state.hasProperty(BlockStateProperties.FACING)) state = state.setValue(BlockStateProperties.FACING, Direction.EAST);
        level.setBlock(at.below(), Blocks.STONE.defaultBlockState(), 3);
        level.setBlock(at, state, 3);
        level.setBlock(at.west(), Blocks.REDSTONE_BLOCK.defaultBlockState(), 3);
        level.setBlock(target, Blocks.STONE.defaultBlockState(), 3);
        BlockEntity be = level.getBlockEntity(at);
        Method tick = be.getClass().getMethod("tick");
        for (int i = 0; i < 5; i++) tick.invoke(be);
        return level.getBlockState(target).is(Blocks.STONE);
    }

    /**
     * Успех через несколько тиков: синхронная загрузка чанка ставит временный билет, и если тест кончился в том же тике,
     * остановка сервера GameTest крутится в выгрузке чанков бесконечно.
     */
    static void done(GameTestHelper h) {
        h.runAfterDelay(3, h::succeed);
    }
}

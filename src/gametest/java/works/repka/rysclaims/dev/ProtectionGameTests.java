package works.repka.rysclaims.dev;

import com.mojang.authlib.GameProfile;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import works.repka.rysclaims.RYSClaims;
import works.repka.rysclaims.data.Chunks;
import works.repka.rysclaims.data.Rights;
import works.repka.rysclaims.data.TeamLand;
import works.repka.rysclaims.server.ClaimService;
import works.repka.rysclaims.server.ClaimsServer;
import works.repka.rysclaims.server.Loader;

/**
 * Защита на тестовом сервере (этап З2, часть 1): каждый случай в двух видах, на чужой земле не прошло, со своим правом
 * прошло. Каждый тест работает в своём далёком квадрате мира, чтобы земля тестов не пересекалась.
 */
@GameTestHolder(RYSClaims.MODID)
@PrefixGameTestTemplate(false)
public final class ProtectionGameTests {
    private ProtectionGameTests() {}

    /** Начало квадрата теста: угол чанка, далеко от площадок и от других тестов. */
    private static BlockPos base(int slot) {
        return new BlockPos(20000 + slot * 256, 100, 20000);
    }

    private static UUID claim(ServerLevel level, BlockPos pos, UUID team) {
        ClaimService s = ClaimsServer.service();
        s.claim(null, team, Loader.id(level.dimension()), LongArrayList.of(Chunks.pack(pos.getX() >> 4, pos.getZ() >> 4)), true);
        return team;
    }

    private static void free(ServerLevel level, BlockPos... positions) {
        ClaimService s = ClaimsServer.service();
        for (BlockPos p : positions) {
            long chunk = Chunks.pack(p.getX() >> 4, p.getZ() >> 4);
            String dim = Loader.id(level.dimension());
            if (s.data().get(dim, chunk) != null) s.data().remove(dim, chunk);
        }
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void breakAndOpen(GameTestHelper h) {
        if (CompatRuns.skipMockPlayers(h, "breakAndOpen")) return;
        ServerLevel level = h.getLevel();
        BlockPos pos = base(0).offset(8, 0, 8);
        UUID team = claim(level, pos, UUID.randomUUID());
        ServerPlayer stranger = h.makeMockServerPlayerInLevel();
        try {
            level.setBlock(pos, Blocks.STONE.defaultBlockState(), 3);
            if (stranger.gameMode.destroyBlock(pos)) h.fail("чужой сломал блок на земле команды");
            if (!level.getBlockState(pos).is(Blocks.STONE)) h.fail("блок пропал");
            // сундук: открывать нельзя
            BlockPos chest = pos.above();
            level.setBlock(chest, Blocks.CHEST.defaultBlockState(), 3);
            stranger.gameMode.useItemOn(stranger, level, ItemStack.EMPTY, InteractionHand.MAIN_HAND,
                new BlockHitResult(Vec3.atCenterOf(chest), Direction.UP, chest, false));
            if (stranger.containerMenu != stranger.inventoryMenu) h.fail("чужой открыл сундук");
            // доверенный со строкой «строить: союзники» ломает и открывает
            TeamLand land = ClaimsServer.service().data().landOrCreate(team);
            land.trusted.add(stranger.getUUID());
            land.rights.set(Rights.Action.BUILD, Rights.ALLIES, true);
            stranger.gameMode.useItemOn(stranger, level, ItemStack.EMPTY, InteractionHand.MAIN_HAND,
                new BlockHitResult(Vec3.atCenterOf(chest), Direction.UP, chest, false));
            if (stranger.containerMenu == stranger.inventoryMenu) h.fail("доверенный не открыл сундук");
            stranger.closeContainer();
            if (!stranger.gameMode.destroyBlock(pos)) h.fail("доверенный не сломал блок");
        } finally {
            level.getServer().getPlayerList().remove(stranger);
            free(level, pos);
        }
        done(h);
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void explosionWithoutOwner(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        BlockPos corner = base(1);
        BlockPos claimed = corner.offset(15, 0, 8); // последний столбец чанка
        BlockPos wild = corner.offset(16, 0, 8); // первый столбец соседнего свободного чанка
        UUID team = claim(level, claimed, UUID.randomUUID());
        try {
            level.setBlock(claimed, Blocks.STONE.defaultBlockState(), 3);
            level.setBlock(wild, Blocks.STONE.defaultBlockState(), 3);
            level.explode(null, wild.getX() + 0.5, wild.getY() + 0.5, wild.getZ() + 0.5, 3f, Level.ExplosionInteraction.TNT);
            if (!level.getBlockState(claimed).is(Blocks.STONE)) h.fail("взрыв без хозяина сломал блок на земле команды");
            if (level.getBlockState(wild).is(Blocks.STONE)) h.fail("взрыв не сломал блок на свободной земле");
            // свой взрыв на своей земле с переключателем ломает
            ClaimsServer.service().data().landOrCreate(team).flag(TeamLand.Flag.OWN_EXPLOSIONS, true);
            BlockPos inside = claimed.offset(-1, 0, 0);
            level.explode(null, inside.getX() + 0.5, inside.getY() + 0.5, inside.getZ() + 0.5, 4f, Level.ExplosionInteraction.TNT);
            if (level.getBlockState(claimed).is(Blocks.STONE)) h.fail("свой взрыв не сломал блок при включённом переключателе");
        } finally {
            free(level, claimed);
        }
        done(h);
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void pistonAcrossBorder(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        BlockPos corner = base(2);
        BlockPos piston = corner.offset(14, 0, 8);
        BlockPos block = corner.offset(15, 0, 8);
        BlockPos into = corner.offset(16, 0, 8);
        claim(level, into, UUID.randomUUID());
        // событие блока поршня зовём сами (triggerEvent, там же PistonEvent.Pre): чанк теста не тикает
        boolean blocked = push(level, piston, block, into);
        // то же внутри свободного чанка двигает: тест что-то проверяет
        BlockPos wildPiston = corner.offset(4, 0, 8);
        boolean moved = !push(level, wildPiston, wildPiston.east(), wildPiston.east(2));
        free(level, into);
        if (!moved) h.fail("поршень не двигает и на свободной земле: тест ничего не проверяет");
        if (!blocked) h.fail("поршень со свободной земли втолкнул блок на землю команды");
        done(h);
    }

    /** Поршень лицом на восток толкает камень; отвечает, остался ли камень на месте и не пришёл ли в {@code into}. */
    private static boolean push(ServerLevel level, BlockPos piston, BlockPos block, BlockPos into) {
        level.setBlock(block, Blocks.STONE.defaultBlockState(), 3);
        level.setBlock(into, Blocks.AIR.defaultBlockState(), 3);
        net.minecraft.world.level.block.state.BlockState state = Blocks.PISTON.defaultBlockState().setValue(PistonBaseBlock.FACING, Direction.EAST);
        level.setBlock(piston, state, 2);
        level.setBlock(piston.west(), Blocks.REDSTONE_BLOCK.defaultBlockState(), 2); // сигнал, без обновления соседей
        level.getBlockState(piston).triggerEvent(level, piston, 0, Direction.EAST.get3DDataValue());
        return level.getBlockState(block).is(Blocks.STONE) && !level.getBlockState(into).is(Blocks.STONE);
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void fakePlayerProfile(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        BlockPos pos = base(3).offset(8, 0, 8);
        UUID owner = UUID.randomUUID();
        claim(level, pos, owner); // без RYSTeams игрок соло: его земля
        try {
            level.setBlock(pos, Blocks.STONE.defaultBlockState(), 3);
            FakePlayer shared = FakePlayerFactory.get(level, new GameProfile(UUID.randomUUID(), "[SharedMachine]"));
            if (shared.gameMode.destroyBlock(pos)) h.fail("машина с общим профилем сломала блок на чужой земле");
            FakePlayer mine = FakePlayerFactory.get(level, new GameProfile(owner, "[OwnerMachine]"));
            if (!mine.gameMode.destroyBlock(pos)) h.fail("машина с профилем хозяина не сломала блок");
            // имя машины в списке команды
            level.setBlock(pos, Blocks.STONE.defaultBlockState(), 3);
            ClaimsServer.service().data().landOrCreate(owner).machineNames.add("[SharedMachine]");
            if (!shared.gameMode.destroyBlock(pos)) h.fail("машина из списка имён не сломала блок");
        } finally {
            free(level, pos);
        }
        done(h);
    }

    /**
     * Успех через несколько тиков: синхронная загрузка чанка ставит временный билет, и если тест кончился в том же тике,
     * остановка сервера GameTest крутится в выгрузке чанков бесконечно.
     */
    static void done(GameTestHelper h) {
        h.runAfterDelay(3, h::succeed);
    }
}

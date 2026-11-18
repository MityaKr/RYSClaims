package works.repka.rysclaims.server;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.MushroomBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.entity.player.BonemealEvent;
import net.neoforged.neoforge.event.level.BlockGrowFeatureEvent;
import net.neoforged.neoforge.event.level.block.CropGrowEvent;
import works.repka.rysclaims.compat.sable.SableLand;
import works.repka.rysclaims.data.Chunks;
import works.repka.rysclaims.data.Rights;

/**
 * Рост через границу (план, раздел 11, строки 22, 23, 25). Деревья, грибы, азалия: кто посадил, игра не хранит, поэтому
 * правило по земле ({@link Borders#growth}): радиус 8 для деревьев 2×2 и огромных грибов, 5 для остальных. Мох от костной
 * муки меняет блоки до 3 блоков в стороны ({@code VegetationPatchFeature}) без событий внутри: мука по мху отменяется, если
 * в 3 блоках есть земля, где у игрока нет «Строить» (без игрока: земля другой команды). {@code CropGrowEvent}: бамбук,
 * тростник, кактус, ламинария растут вверх и из чанка не выходят; вбок растут стебли (плод тыквы и арбуза рядом) и цветок
 * хоруса, их рост с края чанка к земле другой команды отменяется. Лоза ({@code VineBlock}) события не зовёт: не закрыта.
 */
public final class Growth {
    private Growth() {}

    public static void register(IEventBus bus) {
        bus.addListener(EventPriority.HIGH, BlockGrowFeatureEvent.class, Growth::onTree);
        bus.addListener(EventPriority.HIGH, BonemealEvent.class, Growth::onBonemeal);
        bus.addListener(EventPriority.HIGH, CropGrowEvent.Pre.class, Growth::onCrop);
    }

    private static void onTree(BlockGrowFeatureEvent e) {
        if (!(e.getLevel() instanceof Level level) || level.isClientSide) return;
        BlockPos pos = e.getPos();
        if (!Borders.growth(level, SableLand.toWorld(level, pos), radius(level, pos))) e.setCanceled(true);
    }

    /** 8 для саженца в квадрате 2×2 и огромного гриба, иначе 5 (план, строка 22). */
    static int radius(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (state.getBlock() instanceof MushroomBlock || state.is(Blocks.CRIMSON_FUNGUS) || state.is(Blocks.WARPED_FUNGUS)) return 8;
        for (int dx = -1; dx <= 0; dx++) {
            for (int dz = -1; dz <= 0; dz++) {
                boolean square = true;
                for (int i = 0; i <= 1 && square; i++) {
                    for (int j = 0; j <= 1 && square; j++) {
                        if (!level.getBlockState(pos.offset(dx + i, 0, dz + j)).is(state.getBlock())) square = false;
                    }
                }
                if (square) return 8;
            }
        }
        return 5;
    }

    private static void onBonemeal(BonemealEvent e) {
        Level level = e.getLevel();
        if (level.isClientSide || !e.getState().is(Blocks.MOSS_BLOCK)) return;
        BlockPos pos = e.getPos();
        if (e.getPlayer() != null) {
            for (int cx = (pos.getX() - 3) >> 4; cx <= (pos.getX() + 3) >> 4; cx++) {
                for (int cz = (pos.getZ() - 3) >> 4; cz <= (pos.getZ() + 3) >> 4; cz++) {
                    BlockPos at = new BlockPos(Math.max(cx * 16, Math.min(cx * 16 + 15, pos.getX())), pos.getY(), Math.max(cz * 16, Math.min(cz * 16 + 15, pos.getZ())));
                    if (!Protection.check(e.getPlayer(), level, at, Rights.Action.BUILD)) {
                        e.setCanceled(true);
                        return;
                    }
                }
            }
        } else if (!Borders.growth(level, pos, 3)) {
            e.setCanceled(true);
        }
    }

    private static void onCrop(CropGrowEvent.Pre e) {
        if (!(e.getLevel() instanceof Level level) || level.isClientSide) return;
        BlockPos pos = e.getPos();
        int lx = pos.getX() & 15, lz = pos.getZ() & 15;
        if (lx != 0 && lx != 15 && lz != 0 && lz != 15) return;
        BlockState state = e.getState();
        // рост вверх и вниз (бамбук, тростник, кактус, ламинария, плакучие лианы) из чанка не выходит; вбок растут только
        // стебли тыквы и арбуза (плод рядом) и цветок хоруса
        if (!(state.getBlock() instanceof net.minecraft.world.level.block.StemBlock) && !state.is(Blocks.CHORUS_FLOWER)) return;
        if (!Borders.growth(level, pos, 1)) e.setResult(CropGrowEvent.Pre.Result.DO_NOT_GROW);
    }

    static long chunk(BlockPos pos) {
        return Chunks.pack(pos.getX() >> 4, pos.getZ() >> 4);
    }
}

package works.repka.rysclaims.mixin.compat;

import java.lang.reflect.Field;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import works.repka.rysclaims.server.Machines;

/**
 * Industrial Foregoing 3.6.38 (R0, раздел 1.2): машины с рабочей областью (сборщик растений, сеялка, удобритель,
 * сборщик и установщик жидкости, установщик и разрушитель блоков, экстрактор) перед работой в клетке
 * {@code getPointedBlockPos()} спрашивают {@code IndustrialAreaWorkingTile.isLoaded(клетка)} (проверено по байткоду всех
 * девяти). Сборщик и сеялка дальше ставят и убирают {@code setBlockAndUpdate} без событий. Клетка, закрытая машине,
 * отвечает «не загружена»: машина переходит к следующей. Место это блок машины, профиль её хозяин (поле {@code uuid},
 * ставится при установке блока игроком).
 */
@Pseudo
@Mixin(targets = "com.buuz135.industrial.block.tile.IndustrialAreaWorkingTile", remap = false)
abstract class ForegoingAreaMixin {
    @Unique private static Field rysclaims$uuid;
    @Unique private static boolean rysclaims$noField;

    @Inject(method = "isLoaded", at = @At("HEAD"), cancellable = true, require = 0)
    private void rysclaims$loaded(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        if (!((Object) this instanceof BlockEntity be) || be.getLevel() == null || be.getLevel().isClientSide) return;
        BlockPos from = be.getBlockPos();
        // тот же чанк: можно всегда, без отражения и строк (разбор логики 19.11, раздел 5, пункт 4)
        if (((from.getX() ^ pos.getX()) >> 4) == 0 && ((from.getZ() ^ pos.getZ()) >> 4) == 0) return;
        if (!Machines.at(be.getLevel(), from, rysclaims$owner(be), pos,
            () -> "industrialforegoing " + from.getX() + " " + from.getY() + " " + from.getZ(), Machines.FAR)) cir.setReturnValue(false);
    }

    @Unique
    private static @Nullable UUID rysclaims$owner(BlockEntity be) {
        if (rysclaims$noField) return null;
        try {
            if (rysclaims$uuid == null) {
                Class<?> type = Class.forName("com.buuz135.industrial.block.tile.IndustrialMachineTile");
                rysclaims$uuid = type.getDeclaredField("uuid");
                rysclaims$uuid.setAccessible(true);
            }
            return rysclaims$uuid.get(be) instanceof String s && !s.isEmpty() ? UUID.fromString(s) : null;
        } catch (ReflectiveOperationException | RuntimeException e) {
            rysclaims$noField = !(e instanceof IllegalArgumentException);
            return null;
        }
    }
}

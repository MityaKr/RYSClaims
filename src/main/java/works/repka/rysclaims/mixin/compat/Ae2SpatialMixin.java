package works.repka.rysclaims.mixin.compat;

import java.lang.reflect.Method;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import works.repka.rysclaims.server.Machines;

/**
 * Пространственный ввод-вывод AE2 19.2.19 (R0, раздел 2): {@code SpatialStorageCellItem.doSpatialTransition(ячейка,
 * уровень, min, max, игрок)} пишет блоки прямо в секции чанков. Перенос не идёт, если хоть один чанк области на земле, где
 * хозяину сети (номер игрока AE2 переводится в UUID через {@code IPlayerRegistry}) строить нельзя. Без хозяина решает
 * «Машины снаружи».
 */
@Pseudo
@Mixin(targets = "appeng.items.storage.SpatialStorageCellItem", remap = false)
abstract class Ae2SpatialMixin {
    private static Method rysclaims$mapping, rysclaims$profile;

    @Inject(method = "doSpatialTransition(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/BlockPos;I)Z",
        at = @At("HEAD"), cancellable = true, require = 0)
    private void rysclaims$transition(ItemStack cell, ServerLevel level, BlockPos min, BlockPos max, int playerId, CallbackInfoReturnable<Boolean> cir) {
        if (!Machines.area(level, min, max, rysclaims$owner(level, playerId), null, "ae2:spatial_io")) cir.setReturnValue(false);
    }

    private static UUID rysclaims$owner(ServerLevel level, int playerId) {
        if (playerId < 0) return null;
        try {
            if (rysclaims$mapping == null) {
                Class<?> type = Class.forName("appeng.api.features.IPlayerRegistry");
                rysclaims$mapping = type.getMethod("getMapping", Level.class);
                rysclaims$profile = type.getMethod("getProfileId", int.class);
            }
            Object registry = rysclaims$mapping.invoke(null, level);
            return registry == null ? null : (UUID) rysclaims$profile.invoke(registry, playerId);
        } catch (ReflectiveOperationException | ClassCastException e) {
            return null;
        }
    }
}

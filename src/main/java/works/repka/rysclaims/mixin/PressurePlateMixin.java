package works.repka.rysclaims.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import java.util.List;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BasePressurePlateBlock;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import works.repka.rysclaims.server.Borders;

/**
 * Плита под чужим игроком (план, раздел 11, строка 7): обе плиты считают сущности в {@code getEntityCount}, игрок без права
 * «Двери, кнопки, рычаги» и его снаряды на земле команды не считаются. Позиция плиты это низ рамки счёта.
 */
@Mixin(BasePressurePlateBlock.class)
abstract class PressurePlateMixin {
    @WrapOperation(method = "getEntityCount", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/level/Level;getEntitiesOfClass(Ljava/lang/Class;Lnet/minecraft/world/phys/AABB;Ljava/util/function/Predicate;)Ljava/util/List;"))
    private static <T extends Entity> List<T> rysclaims$pressers(Level level, Class<T> type, AABB box, Predicate<? super T> filter, Operation<List<T>> original) {
        List<T> list = original.call(level, type, box, filter);
        if (level.isClientSide) return list;
        return Borders.pressers(level, BlockPos.containing(box.getCenter().x, box.minY, box.getCenter().z), list);
    }
}

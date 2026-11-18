package works.repka.rysclaims.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.TripWireBlock;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import works.repka.rysclaims.server.Borders;

/** Растяжка (план, раздел 11, строка 7): игрок без права «Двери, кнопки, рычаги» и его снаряды её не задевают. */
@Mixin(TripWireBlock.class)
abstract class TripWireMixin {
    @WrapOperation(method = "checkPressed(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;)V", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/level/Level;getEntities(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/AABB;)Ljava/util/List;"))
    private List<Entity> rysclaims$pressers(Level level, Entity except, AABB box, Operation<List<Entity>> original, @Local(argsOnly = true) BlockPos pos) {
        List<Entity> list = original.call(level, except, box);
        return level.isClientSide ? list : Borders.pressers(level, pos, list);
    }
}

package works.repka.rysclaims.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import works.repka.rysclaims.server.Borders;

/** Деревянная кнопка от стрелы (план, раздел 11, строка 7): стрела игрока без права «Двери, кнопки, рычаги» её не жмёт. */
@Mixin(ButtonBlock.class)
abstract class ButtonMixin {
    @WrapOperation(method = "checkPressed", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/level/Level;getEntitiesOfClass(Ljava/lang/Class;Lnet/minecraft/world/phys/AABB;)Ljava/util/List;"))
    private <T extends Entity> List<T> rysclaims$arrows(Level level, Class<T> type, AABB box, Operation<List<T>> original, @Local(argsOnly = true) BlockPos pos) {
        List<T> list = original.call(level, type, box);
        return level.isClientSide ? list : Borders.pressers(level, pos, list);
    }
}

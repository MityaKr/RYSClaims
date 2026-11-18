package works.repka.rysclaims.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.monster.EnderMan;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import works.repka.rysclaims.server.Protection;

/**
 * Эндермен берёт блок (R0, раздел 2): {@code EntityMobGriefingEvent} позиции не знает, поэтому настоящая клетка
 * проверяется здесь. Блок не берётся целиком: эндермен не получает его в руки.
 */
@Mixin(targets = "net.minecraft.world.entity.monster.EnderMan$EndermanTakeBlockGoal")
abstract class EndermanTakeBlockGoalMixin {
    @Shadow
    @Final
    private EnderMan enderman;

    @ModifyExpressionValue(method = "tick", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/level/block/state/BlockState;is(Lnet/minecraft/tags/TagKey;)Z"))
    private boolean rysclaims$take(boolean holdable, @Local BlockPos pos) {
        return holdable && Protection.mobMayGrief(enderman, enderman.level(), pos);
    }
}

package works.repka.rysclaims.mixin.compat;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import works.repka.rysclaims.server.Machines;

/**
 * Каток Create 6.0.10: свой {@code destroyBlock} (столбцы вокруг точки, дроп внутри) и мощение {@code tryFill}
 * (блок берётся из хранилища постройки, затем {@code setBlockAndUpdate}). Отмена в начале обоих: мощение отвечает
 * {@code PaveResult.FAIL}, как на незагруженном чанке, предмет не тратится.
 */
@Pseudo
@Mixin(targets = "com.simibubi.create.content.contraptions.actors.roller.RollerMovementBehaviour", remap = false)
abstract class CreateRollerMixin {
    @Unique private static Object rysclaims$fail;

    @Inject(method = "destroyBlock", at = @At("HEAD"), cancellable = true, require = 0)
    private void rysclaims$destroy(@Coerce Object context, BlockPos pos, CallbackInfo ci) {
        if (!Machines.contraption(context, pos)) ci.cancel();
    }

    @Inject(method = "tryFill", at = @At("HEAD"), cancellable = true, require = 0)
    private void rysclaims$fill(@Coerce Object context, BlockPos pos, BlockState state, CallbackInfoReturnable<Object> cir) {
        if (Machines.contraption(context, pos)) return;
        Object fail = rysclaims$fail();
        if (fail != null) cir.setReturnValue(fail);
    }

    @Unique
    private static Object rysclaims$fail() {
        if (rysclaims$fail == null) {
            try {
                Class<?> type = Class.forName("com.simibubi.create.content.contraptions.actors.roller.RollerMovementBehaviour$PaveResult");
                for (Object c : type.getEnumConstants()) {
                    if ("FAIL".equals(((Enum<?>) c).name())) rysclaims$fail = c;
                }
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                // другая версия Create: мощение не трогаем
            }
        }
        return rysclaims$fail;
    }
}

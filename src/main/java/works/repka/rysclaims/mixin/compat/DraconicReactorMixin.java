package works.repka.rysclaims.mixin.compat;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import works.repka.rysclaims.server.Machines;

/**
 * Взрыв реактора Draconic Evolution 3.1.4 (R0, раздел 2): {@code ExplosionHelper.removeBlock} пишет воздух прямо в секции
 * чанков, радиус до сотен блоков. Блок на земле команды убирается только по правилу взрыва без хозяина (строка 15): центр
 * ({@code start}) на земле той же команды и включён «Свои взрывы ломают блоки», или настройка сервера. Урон сущностям не
 * трогаем.
 */
@Pseudo
@Mixin(targets = "com.brandon3055.draconicevolution.lib.ExplosionHelper", remap = false)
abstract class DraconicReactorMixin {
    @Shadow
    @Final
    private ServerLevel serverWorld;

    @Shadow
    private BlockPos start;

    @Inject(method = "removeBlock(Lnet/minecraft/core/BlockPos;)V", at = @At("HEAD"), cancellable = true, require = 0)
    private void rysclaims$remove(BlockPos pos, CallbackInfo ci) {
        if (serverWorld != null && start != null && !Machines.explosion(serverWorld, start, pos)) ci.cancel();
    }
}

package works.repka.rysclaims.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.common.util.FakePlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import works.repka.rysclaims.data.Rights;
import works.repka.rysclaims.server.Protection;

/**
 * {@code ServerLevel.mayInteract} для фейковых игроков (R0, пункт 0.4): плоскость разрушения AE2, Building Gadgets 2,
 * насос Sophisticated спрашивают её и ломают мимо {@code BreakEvent}. Фейковый игрок судится профилем и списком имён
 * ({@link Protection#allowed}), его позиции не верим. Живого игрока не трогаем: игра спрашивает то же самое перед любым
 * правым кликом по блоку, и право «Строить» здесь закрыло бы двери и верстаки; живого судят события.
 */
@Mixin(ServerLevel.class)
abstract class ServerLevelMixin {
    @Inject(method = "mayInteract", at = @At("HEAD"), cancellable = true)
    private void rysclaims$machine(Player player, BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        if (player instanceof FakePlayer && !Protection.check(player, (ServerLevel) (Object) this, pos, Rights.Action.BUILD)) {
            cir.setReturnValue(false);
        }
    }
}

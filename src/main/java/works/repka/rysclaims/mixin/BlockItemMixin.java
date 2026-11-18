package works.repka.rysclaims.mixin;

import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.common.util.FakePlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import works.repka.rysclaims.data.Rights;
import works.repka.rysclaims.server.Protection;

/**
 * Установка блока фейковым игроком мимо {@code ItemStack.useOn} (R0, пункт 0.4): плоскость формирования AE2 зовёт
 * {@code Item.useOn} напрямую, Integrated Tunnels {@code BlockItem.placeBlock}; {@code EntityPlaceEvent} тогда нет. Перехват
 * стоит в {@code placeBlock}, через него идут оба пути. При обычной установке то же решение примет и событие, второй раз
 * проверка ничего не меняет.
 */
@Mixin(BlockItem.class)
abstract class BlockItemMixin {
    @Inject(method = "placeBlock", at = @At("HEAD"), cancellable = true)
    private void rysclaims$machine(BlockPlaceContext ctx, BlockState state, CallbackInfoReturnable<Boolean> cir) {
        if (ctx.getPlayer() instanceof FakePlayer fake && !Protection.check(fake, ctx.getLevel(), ctx.getClickedPos(), Rights.Action.BUILD)) {
            cir.setReturnValue(false);
        }
    }
}

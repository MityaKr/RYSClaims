package works.repka.rysclaims.mixin.compat;

import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import works.repka.rysclaims.compat.sable.SableAssembly;

/**
 * Сборка летающей постройки Sable 2.0.6 (R0, разделы 2, 3): {@code SubLevelAssemblyHelper.assembleBlocks(уровень, якорь,
 * блоки, рамка)} двигает блоки в участок без событий. Блоки на земле другой команды, чем якорь, из сборки выкидываются и
 * остаются в мире; после сборки подуровень получает «дом»: команду земли якоря.
 */
@Pseudo
@Mixin(targets = "dev.ryanhcode.sable.api.SubLevelAssemblyHelper", remap = false)
abstract class SableAssemblyMixin {
    @ModifyVariable(method = "assembleBlocks", at = @At("HEAD"), argsOnly = true, require = 0)
    private static Iterable<BlockPos> rysclaims$filter(Iterable<BlockPos> blocks, @Local(argsOnly = true) ServerLevel level,
                                                     @Local(argsOnly = true) BlockPos anchor) {
        return SableAssembly.filter(level, anchor, blocks);
    }

    @Inject(method = "assembleBlocks", at = @At("RETURN"), require = 0)
    @SuppressWarnings("rawtypes")
    private static void rysclaims$home(ServerLevel level, BlockPos anchor, Iterable<BlockPos> blocks, @Coerce Object bounds,
                                       CallbackInfoReturnable cir) {
        SableAssembly.assembled(level, anchor, cir.getReturnValue());
    }
}

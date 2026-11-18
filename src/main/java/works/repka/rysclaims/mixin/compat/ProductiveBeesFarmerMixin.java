package works.repka.rysclaims.mixin.compat;

import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import works.repka.rysclaims.server.Machines;

/**
 * Пчела-фермер Productive Bees 13.14.0: {@code harvestBlock(клетка)} срезает урожай {@code level.destroyBlock(.., true)}
 * без событий (R0, раздел 1.3, {@code MinecraftHarvester}). Отмена в начале. Место это улей пчелы (позиция самой пчелы
 * летает и доверия не стоит), хозяина у пчелы нет.
 */
@Pseudo
@Mixin(targets = "cy.jdkdigital.productivebees.common.entity.bee.hive.FarmerBee", remap = false)
abstract class ProductiveBeesFarmerMixin {
    @Inject(method = "harvestBlock", at = @At("HEAD"), cancellable = true, require = 0)
    private void rysclaims$harvest(BlockPos pos, CallbackInfo ci) {
        if (!Machines.bee(this, pos)) ci.cancel();
    }
}

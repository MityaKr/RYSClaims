package works.repka.rysclaims.mixin.compat;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import works.repka.rysclaims.compat.sable.SableDisassembly;

/**
 * Сборщик Simulated 1.3.2 ({@code PhysicsAssemblerBlockEntity}): {@code throwDisassemblyExceptions(подуровень)} бросает
 * {@code AssemblyException}, если разбирать нельзя (слишком быстро, вне мира, в воздухе). Зовётся при нажатии и перед
 * постановкой в мир (CFR). Над землёй, закрытой постройке, бросается и наше исключение: сборщик показывает его как свою
 * ошибку разборки и останавливает её ({@link SableDisassembly}).
 */
@Pseudo
@Mixin(targets = "dev.simulated_team.simulated.content.blocks.physics_assembler.PhysicsAssemblerBlockEntity", remap = false)
abstract class SableAssemblerMixin {
    @Inject(method = "throwDisassemblyExceptions", at = @At("HEAD"), require = 0)
    private void rysclaims$land(@Coerce Object subLevel, CallbackInfo ci) {
        Exception e = SableDisassembly.assembler(this, subLevel);
        if (e != null) SableDisassembly.sneaky(e);
    }
}

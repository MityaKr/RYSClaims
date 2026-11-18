package works.repka.rysclaims.mixin.compat;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Rotation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import works.repka.rysclaims.compat.sable.SableDisassembly;

/**
 * {@code SimAssemblyHelper.disassembleSubLevel(уровень, подуровень, якорь, цель, поворот, звук)} Simulated 1.3.2 ставит блоки
 * подуровня в мир без событий (CFR, R0 строка 114). Последняя преграда для путей без своей проверки (склеивающий клей):
 * над землёй, закрытой постройке, разборки нет.
 */
@Pseudo
@Mixin(targets = "dev.simulated_team.simulated.util.SimAssemblyHelper", remap = false)
abstract class SableDisassembleMixin {
    @Inject(method = "disassembleSubLevel", at = @At("HEAD"), cancellable = true, require = 0)
    private static void rysclaims$refuse(Level level, @Coerce Object subLevel, BlockPos anchor, BlockPos goal, Rotation rotation,
                                         boolean sound, CallbackInfo ci) {
        if (SableDisassembly.place(level, subLevel, anchor, goal, rotation)) ci.cancel();
    }
}

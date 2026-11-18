package works.repka.rysclaims.mixin.compat;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import works.repka.rysclaims.compat.sable.SableDisassembly;

/**
 * Пакет сборщика Simulated 1.3.2 ({@code AssemblePacket.handle(ServerPacketContext)}, CFR): игрок, который дёрнул рычаг.
 * Сборка и начало разборки идут внутри обработки, игрок нужен дому постройки и разборке у себя ({@link SableDisassembly}).
 */
@Pseudo
@Mixin(targets = "dev.simulated_team.simulated.network.packets.AssemblePacket", remap = false)
abstract class SablePacketMixin {
    @Inject(method = "handle", at = @At("HEAD"), require = 0)
    private void rysclaims$start(@Coerce Object context, CallbackInfo ci) {
        SableDisassembly.packetStart(context);
    }

    @Inject(method = "handle", at = @At("RETURN"), require = 0)
    private void rysclaims$end(@Coerce Object context, CallbackInfo ci) {
        SableDisassembly.packetEnd();
    }
}

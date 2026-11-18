package works.repka.rysclaims.client;

import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;
import net.neoforged.neoforge.common.NeoForge;
import works.repka.rysclaims.RYSClaims;

/** Клиент RYSClaims: слой отказа у прицела. Окно земли и кэш регионов придут с З4 (план, раздел 9.2). */
public final class ClaimsClient {
    private ClaimsClient() {}

    public static void init(IEventBus modBus) {
        modBus.addListener((RegisterGuiLayersEvent e) ->
            e.registerAbove(VanillaGuiLayers.CROSSHAIR, ResourceLocation.fromNamespaceAndPath(RYSClaims.MODID, "deny"), DenyFeedback::render));
        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut e) -> DenyFeedback.reset());
    }
}

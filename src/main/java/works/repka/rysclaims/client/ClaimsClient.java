package works.repka.rysclaims.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;
import net.neoforged.neoforge.client.settings.KeyConflictContext;
import net.neoforged.neoforge.common.NeoForge;
import works.repka.ryscore.dock.DockButton;
import works.repka.ryscore.ui.Icons;
import works.repka.rysclaims.RYSClaims;

/**
 * Клиент RYSClaims: слой отказа у прицела, кэш земли и подписка, окно земли, слой карты через {@code MapApi}, кнопка
 * «Земля» в панели семьи. Клавиши «Земля команды» и «Занять чанк под ногами» по умолчанию не назначены (план 12,
 * «Клавиши»): окно в одном клике от карты и от панели.
 */
public final class ClaimsClient {
    public static final String CATEGORY = "key.categories.rysclaims";
    public static final KeyMapping OPEN = new KeyMapping("key.rysclaims.open", KeyConflictContext.IN_GAME, InputConstants.Type.KEYSYM,
        InputConstants.UNKNOWN.getValue(), CATEGORY);
    public static final KeyMapping CLAIM_HERE = new KeyMapping("key.rysclaims.claim_here", KeyConflictContext.IN_GAME, InputConstants.Type.KEYSYM,
        InputConstants.UNKNOWN.getValue(), CATEGORY);

    private ClaimsClient() {}

    private static works.repka.ryscore.team.TeamCards.@org.jetbrains.annotations.Nullable Detailed card() {
        works.repka.rysclaims.net.ClaimsNet.Hello h = LandCache.hello();
        if (h == null) return null;
        java.util.List<Component> lines = java.util.List.of(
            Component.translatable("rysclaims.card.claims", h.claimed(), h.claimLimit()),
            Component.translatable("rysclaims.card.force", h.forced(), h.forceLimit()));
        var st = LandCache.state();
        Component tag = st == null || st.plots().isEmpty() ? null : Component.literal(Words.plural(st.plots().size(), "plots"));
        var card = new works.repka.ryscore.team.TeamCards.Card(works.repka.rysclaims.server.Feed.SOURCE, Component.translatable("rysclaims.card.title"),
            lines, Component.translatable("rysclaims.card.open"), LandScreen::open, h.claimed() > h.claimLimit());
        return new works.repka.ryscore.team.TeamCards.Detailed(card, tag, h.claimLimit() <= 0 ? -1f : Math.min(1f, h.claimed() / (float) h.claimLimit()));
    }

    public static void init(IEventBus modBus) {
        modBus.addListener((RegisterGuiLayersEvent e) ->
            e.registerAbove(VanillaGuiLayers.CROSSHAIR, ResourceLocation.fromNamespaceAndPath(RYSClaims.MODID, "deny"), DenyFeedback::render));
        modBus.addListener((RegisterKeyMappingsEvent e) -> {
            e.register(OPEN);
            e.register(CLAIM_HERE);
        });
        // с RYSMap земля живёт вкладкой окна карты, своя плитка в панели была бы второй дверью туда же (разбор логики
        // 19.11, раздел 5, пункт 1); клавиша «Земля» остаётся и открывает вкладку
        if (!net.neoforged.fml.ModList.get().isLoaded("rysmap")) {
            modBus.addListener((FMLClientSetupEvent e) -> e.enqueueWork(() -> DockButton.builder("rysclaims_land")
                .label(Component.translatable("rysclaims.dock")).description(() -> Component.translatable("rysclaims.dock.description"))
                .tile(0xFFD9714F).icon(Icons.Shape.SHIELD).key(OPEN).order(21).action(LandScreen::open).register()));
        }
        LandLayer.register();
        // земля внутри окна карты: режим «Приват» и вкладка «Земля» (З7, ядро 1.12)
        LandMap.register();
        // сводка «Земля» на экране команды RYSTeams (план, раздел 8, строка «Экран команды»)
        works.repka.ryscore.team.TeamCards.registerDetailed(ClaimsClient::card);
        var bus = NeoForge.EVENT_BUS;
        bus.addListener(InputGate::onInput);
        bus.addListener(InputGate::onRightClickBlock);
        bus.addListener((ClientPlayerNetworkEvent.LoggingOut e) -> {
            DenyFeedback.reset();
            LandCache.reset();
            LandScreen.reset();
            LandMap.reset();
            LandLayer.reset();
        });
        bus.addListener((ClientTickEvent.Post e) -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null) return;
            LandCache.tick();
            while (OPEN.consumeClick()) if (mc.screen == null) LandScreen.open();
            while (CLAIM_HERE.consumeClick()) if (mc.screen == null) LandCache.claimHere();
        });
    }
}

package works.repka.rysclaims;

import java.util.Set;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import works.repka.ryscore.claims.ClaimsApi;
import works.repka.ryscore.migrate.Migrations;
import works.repka.ryscore.team.TeamsApi;
import works.repka.rysclaims.api.ClaimsApiImpl;
import works.repka.rysclaims.migrate.ClaimsStep;
import works.repka.rysclaims.server.ClaimService;
import works.repka.rysclaims.server.ClaimsServer;
import works.repka.rysclaims.server.Commands;
import works.repka.rysclaims.server.Loader;
import works.repka.rysclaims.server.Protection;
import works.repka.rysclaims.server.ServerConfig;
import works.repka.rysclaims.server.TeamHooks;

/** Точка входа: общая для клиента и сервера. В 0.0.x только серверная часть: земля, лимиты, прогрузка, команды, переезд. */
@Mod(RYSClaims.MODID)
public final class RYSClaims {
    public static final String MODID = "rysclaims";
    public static final Logger LOG = LoggerFactory.getLogger(RYSClaims.class);

    public RYSClaims(IEventBus modBus, ModContainer container) {
        container.registerConfig(ModConfig.Type.SERVER, ServerConfig.SPEC);
        modBus.addListener((ModConfigEvent.Loading e) -> ServerConfig.invalidate());
        modBus.addListener((ModConfigEvent.Reloading e) -> ServerConfig.invalidate());
        modBus.addListener(Loader::register);

        // права управления землёй живут в таблице прав RYSTeams (решение 18 плана); без RYSTeams игрок соло и может всё
        TeamsApi teams = TeamsApi.get();
        teams.registerPermission(ClaimService.PERM_CLAIM, Component.translatableWithFallback("rysclaims.perm.claim", "Claim and release land"),
            Set.of(TeamsApi.LEADER, TeamsApi.OFFICER, TeamsApi.MEMBER));
        teams.registerPermission(ClaimService.PERM_FORCE, Component.translatableWithFallback("rysclaims.perm.force", "Force load land"),
            Set.of(TeamsApi.LEADER, TeamsApi.OFFICER, TeamsApi.MEMBER));
        teams.registerPermission(ClaimService.PERM_RIGHTS, Component.translatableWithFallback("rysclaims.perm.rights", "Change land rights"),
            Set.of(TeamsApi.LEADER));
        TeamsApi.listen(new TeamHooks());
        // переезд с FTB Chunks: шаг ядра, порядок 30
        Migrations.register(new ClaimsStep());

        // земля поднимается раньше переезда ядра (ServerStartedEvent) и раньше остальных модов
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGH, (ServerStartingEvent e) -> ClaimsServer.start(e.getServer()));
        NeoForge.EVENT_BUS.addListener((ServerStoppedEvent e) -> {
            ClaimsServer.stop();
            Protection.clear();
        });
        Protection.register(NeoForge.EVENT_BUS);
        NeoForge.EVENT_BUS.addListener((ServerTickEvent.Post e) -> ClaimsServer.tick());
        NeoForge.EVENT_BUS.addListener((PlayerEvent.PlayerLoggedInEvent e) -> {
            if (e.getEntity() instanceof ServerPlayer p && !(p instanceof FakePlayer)) ClaimsServer.login(p);
        });
        NeoForge.EVENT_BUS.addListener((PlayerEvent.PlayerLoggedOutEvent e) -> ClaimsServer.loaderDirty());
        NeoForge.EVENT_BUS.addListener((RegisterCommandsEvent e) -> Commands.register(e.getDispatcher()));
        ClaimsApi.provide(new ClaimsApiImpl());
        LOG.info("RYSClaims загружен");
    }
}

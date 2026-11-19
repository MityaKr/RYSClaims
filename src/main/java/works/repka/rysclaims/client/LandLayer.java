package works.repka.rysclaims.client;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;
import works.repka.ryscore.map.MapApi;
import works.repka.ryscore.ui.Icons;
import works.repka.rysclaims.RYSClaims;
import works.repka.rysclaims.net.ClaimsNet;

/**
 * Земля в карте RYSMap через {@code MapApi} ядра (план, раздел 9.3): слой «Участки» (заливка и обводка цветом команды,
 * освобождаемое штрихом, прогрузка значком), подсказка чанка (макет {@code 13}), пункт меню «Занять чанк» (открывает окно
 * земли на этом месте), строки под миникартой «чья земля» (порядок 10) и «оператор» (порядок 5). Без RYSMap ядро просто
 * хранит регистрации. Данные берутся из {@link LandCache}; регионы, которые рисует карта, сами попадают в подписку.
 */
public final class LandLayer implements MapApi.Layer, MapApi.Areas {
    public static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath(RYSClaims.MODID, "claims");
    /** Строка «чья земля» держится ещё 3 секунды после выхода с земли (план, раздел 5). */
    private static final long STATUS_HOLD_MS = 3000;
    private static MapApi.@Nullable Status lastStatus;
    private static long lastStatusAt;

    private LandLayer() {}

    public static void register() {
        MapApi api = MapApi.get();
        api.layer(new LandLayer());
        api.menu(MapApi.MenuItem.builder(ResourceLocation.fromNamespaceAndPath(RYSClaims.MODID, "claim"), Component.translatable("rysclaims.menu.claim"))
            .icon(Icons.Shape.SHIELD).order(20)
            .visible(s -> LandCache.available())
            .enabled(s -> {
                ClaimsNet.Hello h = LandCache.hello();
                return h != null && h.canClaim() && LandCache.at(id(s.dimension()), s.chunkX(), s.chunkZ()) == null;
            })
            .run(s -> LandScreen.open(s.dimension(), s.chunkX(), s.chunkZ(), true)).build());
        api.status(ResourceLocation.fromNamespaceAndPath(RYSClaims.MODID, "land"), 10, LandLayer::status);
        api.status(ResourceLocation.fromNamespaceAndPath(RYSClaims.MODID, "bypass"), 5, LandLayer::bypass);
    }

    static String id(ResourceKey<Level> dimension) {
        return dimension.location().toString();
    }

    // ---- слой ----

    @Override
    public ResourceLocation id() {
        return ID;
    }

    @Override
    public Component name() {
        return Component.translatable("rysclaims.layer");
    }

    @Override
    public Icons.Shape icon() {
        return Icons.Shape.SHIELD;
    }

    @Override
    public int order() {
        return 20;
    }

    @Override
    public long revision() {
        return 0;
    }

    @Override
    public Collection<MapApi.Marker> markers(ResourceKey<Level> dimension) {
        return List.of();
    }

    @Override
    public MapApi.Areas areas() {
        return this;
    }

    @Override
    public long revision(ResourceKey<Level> dimension, int regionX, int regionZ) {
        return LandCache.revision(id(dimension), regionX, regionZ);
    }

    @Override
    public void fill(ResourceKey<Level> dimension, int regionX, int regionZ, MapApi.AreaSink sink) {
        String dim = id(dimension);
        for (int z = 0; z < 32; z++) {
            for (int x = 0; x < 32; x++) {
                int cx = regionX * 32 + x, cz = regionZ * 32 + z;
                LandCache.ChunkState st = LandCache.at(dim, cx, cz);
                if (st == null) continue;
                sink.chunk(cx, cz, st.team().id().hashCode(), st.team().color(), st.releasing() ? MapApi.AreaStyle.HATCH : MapApi.AreaStyle.FILL, st.force());
            }
        }
    }

    @Override
    public @Nullable List<Component> tooltip(ResourceKey<Level> dimension, int chunkX, int chunkZ) {
        return lines(id(dimension), chunkX, chunkZ, true);
    }

    /**
     * Подсказка чанка (макет {@code 13}): чья земля, номер чанка, прогрузка, «освобождается», что можно тебе. На карте
     * неизвестный чанк (регион ещё не пришёл) подсказки не даёт.
     */
    static @Nullable List<Component> lines(String dim, int cx, int cz, boolean map) {
        if (!LandCache.known(dim, cx, cz)) return map ? null : List.of(Component.translatable("rysclaims.tip.loading"));
        List<Component> out = new ArrayList<>();
        LandCache.ChunkState st = LandCache.at(dim, cx, cz);
        ClaimsNet.Hello h = LandCache.hello();
        if (st == null) {
            out.add(Component.translatable("rysclaims.tip.free"));
            out.add(Component.translatable("rysclaims.tip.chunk", cx, cz));
            if (map && h != null && h.canClaim()) out.add(Component.translatable("rysclaims.tip.free.claim"));
            return out;
        }
        boolean own = h != null && st.team().id().equals(h.team());
        if (!own) {
            // чужая земля (разбор скептика 18.11): чья и одной строкой, что можно тебе
            out.add(Component.translatable("rysclaims.tip.other", shownName(st)));
            if (st.releasing()) out.add(Component.translatable("rysclaims.tip.releasing"));
            out.add(rightsLine(st.team()));
            return out;
        }
        out.add(Component.translatable(h.solo() ? "rysclaims.tip.own.solo" : "rysclaims.tip.own", shownName(st)));
        out.add(Component.translatable("rysclaims.tip.chunk", cx, cz));
        out.add(Component.translatable(st.force() ? "rysclaims.tip.force.on" : "rysclaims.tip.force.off"));
        if (st.releasing()) out.add(Component.translatable("rysclaims.tip.releasing"));
        // на своей земле строка прав только если роли что-то нельзя
        if (!st.team().can(ClaimsNet.BUILD) || !st.team().can(ClaimsNet.OPEN) || !st.team().can(ClaimsNet.USE) || !st.team().can(ClaimsNet.ENTITY)) {
            out.add(rightsLine(st.team()));
        }
        return out;
    }

    private static final String[] RIGHT_KEYS = {"build", "open", "use", "entity"};
    private static final int[] RIGHT_BITS = {ClaimsNet.BUILD, ClaimsNet.OPEN, ClaimsNet.USE, ClaimsNet.ENTITY};

    /** Что можно тебе на земле команды одной строкой: «Тебе здесь: только смотреть» или «Тебе здесь можно: открывать, двери». */
    static Component rightsLine(LandCache.TeamInfo team) {
        if (team.can(ClaimsNet.MIXED)) return Component.translatable("rysclaims.tip.mixed");
        List<String> can = new ArrayList<>();
        for (int i = 0; i < RIGHT_KEYS.length; i++) {
            if (team.can(RIGHT_BITS[i])) can.add(Component.translatable("rysclaims.tip.can." + RIGHT_KEYS[i]).getString());
        }
        if (can.isEmpty()) return Component.translatable("rysclaims.tip.look");
        if (can.size() == RIGHT_KEYS.length) return Component.translatable("rysclaims.tip.all");
        return Component.translatable("rysclaims.tip.can", String.join(", ", can));
    }

    // ---- строки под миникартой ----

    private static MapApi.@Nullable Status status() {
        LocalPlayer p = Minecraft.getInstance().player;
        if (p == null) return null;
        LandCache.ChunkState st = LandCache.at(id(p.level().dimension()), p.getBlockX() >> 4, p.getBlockZ() >> 4);
        long now = System.currentTimeMillis();
        if (st == null) return lastStatus != null && now - lastStatusAt <= STATUS_HOLD_MS ? lastStatus : null;
        ClaimsNet.Hello h = LandCache.hello();
        boolean own = h != null && st.team().id().equals(h.team());
        Component text = Component.translatable(own ? "rysclaims.status.own" : "rysclaims.status.other", shownName(st));
        lastStatus = MapApi.Status.of(st.team().color(), Icons.Shape.SHIELD, text);
        lastStatusAt = now;
        return lastStatus;
    }

    private static MapApi.@Nullable Status bypass() {
        ClaimsNet.Hello h = LandCache.hello();
        if (h == null || !h.bypass()) return null;
        return MapApi.Status.of(LandView.deny(works.repka.ryscore.theme.ThemeManager.get()), Icons.Shape.WARN, Component.translatable("rysclaims.status.bypass"));
    }

    public static void reset() {
        lastStatus = null;
    }

    /** Имя хозяина для подписи: скрытая земля приходит без имени и подписывается «Занято». */
    static Component shownName(LandCache.ChunkState st) {
        return st.team().name().isEmpty() ? Component.translatable("rysclaims.screen.taken") : Component.literal(st.team().name());
    }
}

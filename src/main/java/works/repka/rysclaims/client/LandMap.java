package works.repka.rysclaims.client;

import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;
import works.repka.ryscore.map.MapApi;
import works.repka.ryscore.theme.Theme;
import works.repka.ryscore.ui.Icons;
import works.repka.ryscore.ui.Kit;
import works.repka.rysclaims.RYSClaims;

/**
 * Земля внутри окна карты RYSMap (план 7.3, 7.4, этап З7, контракт ядра 1.12): режим полотна «Приват» рядом с «Обзор»
 * (сетка чанков, своя и чужая земля, выделение, правая панель с лимитами и инструментами, не дальше 12 единиц на чанк,
 * тайлы приглушены, области слоёв не рисуются) и вкладка «Земля» рядом с «Карта» (участки, права, журнал, у оператора
 * сервер и команды). Обе видны, только если сервер знает RYSClaims. Без RYSMap та же логика живёт в своём окне
 * {@link LandScreen}.
 */
public final class LandMap {
    public static final ResourceLocation MODE = ResourceLocation.fromNamespaceAndPath(RYSClaims.MODID, "claims");
    public static final ResourceLocation TAB = ResourceLocation.fromNamespaceAndPath(RYSClaims.MODID, "land");

    private static @Nullable LandView tabView;
    private static @Nullable LandView modeView;

    private LandMap() {}

    /** Земля открывается в окне карты: RYSMap есть, сервер знает RYSClaims. */
    public static boolean active() {
        return MapApi.get().present() && LandCache.available();
    }

    /** Режим «Приват» сейчас открыт (итог правки идёт в панель, а не тостом). */
    public static boolean modeOpen() {
        return modeView != null;
    }

    static void register() {
        MapApi.get().mode(new Mode());
        MapApi.get().tab(new Tab());
    }

    public static void reset() {
        tabView = null;
        modeView = null;
    }

    private static LandView view(LandView.Kind kind) {
        LocalPlayer p = Minecraft.getInstance().player;
        var dim = p == null ? Level.OVERWORLD : p.level().dimension();
        double x = p == null ? 0 : p.getX(), z = p == null ? 0 : p.getZ();
        return new LandView(kind, dim, x, z, () -> {
            if (Minecraft.getInstance().screen != null) Minecraft.getInstance().screen.onClose();
        });
    }

    private static final class Mode implements MapApi.Mode {
        @Override
        public ResourceLocation id() {
            return MODE;
        }

        @Override
        public Component name() {
            return Component.translatable("rysclaims.map.mode");
        }

        @Override
        public Icons.Shape icon() {
            return Icons.Shape.SHIELD;
        }

        @Override
        public int order() {
            return 10;
        }

        @Override
        public boolean available() {
            return LandCache.available();
        }

        @Override
        public MapApi.ModeView open(MapApi.Surface surface) {
            LandView v = view(LandView.Kind.MODE);
            v.modeFrame(surface);
            modeView = v;
            return new MapApi.ModeView() {
                @Override
                public void render(GuiGraphics g, Theme t, MapApi.Surface s, double mouseX, double mouseY) {
                    v.modeFrame(s);
                    v.renderCanvas(g, t, (int) mouseX, (int) mouseY);
                }

                @Override
                public boolean mouse(MapApi.Surface s, double x, double y, int button, MapApi.MouseAction action) {
                    v.modeFrame(s);
                    return v.modeMouse(x, y, button, action);
                }

                @Override
                public boolean key(int key, int scan, int modifiers) {
                    return v.modeKey(key);
                }

                @Override
                public @Nullable List<Component> tooltip(MapApi.Surface s, double x, double y) {
                    v.modeFrame(s);
                    return v.modeTooltip(x, y);
                }

                @Override
                public MapApi.PanelView panel() {
                    return (g, t, x, y, w, h, hits, mouseX, mouseY) -> v.renderPanel(g, t, x, y, w, h, hits, (int) mouseX, (int) mouseY);
                }

                @Override
                public float minChunkSize() {
                    return 12;
                }

                @Override
                public boolean dimTiles() {
                    return true;
                }

                @Override
                public boolean drawAreas() {
                    return false;
                }

                @Override
                public void close() {
                    v.modeClosed();
                    if (modeView == v) modeView = null;
                }
            };
        }
    }

    private static final class Tab implements MapApi.Tab {
        @Override
        public ResourceLocation id() {
            return TAB;
        }

        @Override
        public Component name() {
            return Component.translatable("rysclaims.map.tab");
        }

        @Override
        public int order() {
            return 10;
        }

        @Override
        public boolean available() {
            return LandCache.available();
        }

        private LandView v() {
            LandView v = tabView;
            if (v == null) tabView = v = view(LandView.Kind.TAB);
            return v;
        }

        @Override
        public void render(GuiGraphics g, Theme t, int x, int y, int w, int h, boolean compact, Kit.Hits hits, double mouseX, double mouseY) {
            LandView v = v();
            v.hits = hits;
            // раскладка подразделов считается от верха окна с шапкой: вкладка начинается под шапкой окна карты
            v.layout(x, y - LandView.HEADER, w, h + LandView.HEADER, g.guiWidth(), g.guiHeight());
            v.render(g, (int) mouseX, (int) mouseY);
        }

        @Override
        public boolean mouse(double x, double y, int button, MapApi.MouseAction action) {
            LandView v = v();
            return switch (action) {
                case PRESS -> v.onClick(x, y, button);
                case DRAG -> v.onDrag(x, y, button, 0, 0);
                case RELEASE -> v.onRelease(x, y, button);
                default -> false;
            };
        }

        @Override
        public boolean scroll(double x, double y, double dy) {
            return v().onScroll(x, y, 0, dy);
        }

        @Override
        public boolean key(int key, int scan, int modifiers) {
            return v().keyPressed(key, scan, modifiers);
        }

        @Override
        public boolean charTyped(char c, int modifiers) {
            return v().charTyped(c, modifiers);
        }

        @Override
        public void opened() {
            LandCache.wantLog(5000);
        }
    }
}

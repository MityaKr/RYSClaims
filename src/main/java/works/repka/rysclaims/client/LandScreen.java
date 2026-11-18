package works.repka.rysclaims.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import works.repka.ryscore.map.MapApi;
import works.repka.ryscore.ui.ScaledScreen;
import works.repka.rysclaims.data.Chunks;

/**
 * Своё окно земли (план 13.1, макет {@code 35}): нужно, когда RYSMap нет. С RYSMap земля живёт в окне карты: вкладка
 * «Земля» и режим «Приват» ({@link LandMap}), туда же ведут кнопка панели семьи и клавиша. Вся логика в {@link LandView}.
 */
public final class LandScreen extends ScaledScreen {
    private final LandView view;

    private LandScreen(ResourceKey<Level> dimension, double x, double z) {
        super(Component.translatable("rysclaims.screen.title"));
        this.view = new LandView(LandView.Kind.WINDOW, dimension, x, z, this::onClose);
    }

    /** Открыть землю (клавиша, кнопка панели): вкладка «Земля» окна карты, без RYSMap своё окно на игроке. */
    public static void open() {
        LocalPlayer p = Minecraft.getInstance().player;
        if (p == null) return;
        if (LandMap.active()) {
            MapApi.get().openTab(LandMap.TAB);
            return;
        }
        Minecraft.getInstance().setScreen(new LandScreen(p.level().dimension(), p.getX(), p.getZ()));
    }

    /** Открыть на чанке (меню карты «Занять чанк»): чанк выделен, инструмент «Занять». С RYSMap это режим «Приват». */
    public static void open(ResourceKey<Level> dimension, int chunkX, int chunkZ, boolean select) {
        if (select) LandView.selectOne(dimension.location().toString(), chunkX, chunkZ);
        if (LandMap.active()) {
            MapApi.get().openMode(LandMap.MODE, MapApi.Target.builder(dimension, chunkX * 16 + 8, 64, chunkZ * 16 + 8).build());
            return;
        }
        LandScreen s = new LandScreen(dimension, chunkX * 16 + 8, chunkZ * 16 + 8);
        s.view.gridPage();
        Minecraft.getInstance().setScreen(s);
    }

    /** Своё окно, даже если RYSMap есть (для проверок). */
    public static LandScreen window(ResourceKey<Level> dimension, double x, double z) {
        return new LandScreen(dimension, x, z);
    }

    public static void reset() {
        LandView.reset();
    }

    @Override
    protected void init() {
        super.init();
        int ww = Math.min(LandView.W, width - 16), wh = Math.min(LandView.H, height - 16);
        view.layout((width - ww) / 2, (height - wh) / 2, ww, wh, width, height);
    }

    @Override
    protected void draw(GuiGraphics g, int mx, int my, float partial) {
        view.hits.clear();
        view.render(g, mx, my);
    }

    @Override
    protected boolean onClick(double x, double y, int button) {
        return view.onClick(x, y, button) || super.onClick(x, y, button);
    }

    @Override
    protected boolean onDrag(double x, double y, int button, double dx, double dy) {
        return view.onDrag(x, y, button, dx, dy) || super.onDrag(x, y, button, dx, dy);
    }

    @Override
    protected boolean onRelease(double x, double y, int button) {
        return view.onRelease(x, y, button) || super.onRelease(x, y, button);
    }

    @Override
    protected boolean onScroll(double x, double y, double sx, double sy) {
        return view.onScroll(x, y, sx, sy) || super.onScroll(x, y, sx, sy);
    }

    @Override
    public boolean keyPressed(int key, int scan, int modifiers) {
        return view.keyPressed(key, scan, modifiers) || super.keyPressed(key, scan, modifiers);
    }

    @Override
    public boolean charTyped(char c, int modifiers) {
        return view.charTyped(c, modifiers) || super.charTyped(c, modifiers);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /** Для проверок: открытое окно на подразделе «Права». */
    public void rightsForTest() {
        view.rightsForTest();
    }
}

package works.repka.rysclaims.client;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;
import works.repka.ryscore.map.MapApi;
import works.repka.ryscore.theme.Theme;
import works.repka.ryscore.theme.ThemeManager;
import works.repka.ryscore.ui.Draw;
import works.repka.ryscore.ui.Icons;
import works.repka.ryscore.ui.Kit;
import works.repka.ryscore.ui.ScaledScreen;
import works.repka.ryscore.ui.Type.Weight;
import works.repka.rysclaims.data.Chunks;
import works.repka.rysclaims.data.Rights;
import works.repka.rysclaims.net.ClaimsNet;

/**
 * Окно земли (план 13.1, макеты {@code 35}, {@code 30}–{@code 33}, {@code 36}, {@code 41}, {@code 41b}), два подраздела.
 *
 * <p>«Сетка»: полотно чанков вокруг игрока, фон это тайлы карты ({@code MapApi.drawTiles}), если RYSMap есть, иначе тёмное
 * полотно. Своя земля цветом команды, чужая серой штриховкой с именем, освобождаемая красной штриховкой, прогрузка
 * значком. Выделение: тянуть прямоугольник, клик добавляет или убирает чанк, Ctrl убирает; живёт до выхода из мира.
 * Справа панель: команда и роль, два лимита с полосами, три инструмента (занять, прогрузка, освободить), итог выделения,
 * легенда, главная кнопка с Enter. Освобождение идёт с отсчётом, внизу полотна строка «освободятся через … · Оставить».
 *
 * <p>«Права»: четыре действия на три столбца (команда, союзники, все; у одиночки «ты, доверенные, все»), три готовых
 * набора и «Свои», строка «Вернуть» 30 секунд после правки. Править может тот, кому это разрешено в RYSTeams (по
 * умолчанию лидер) и если сервер не закрыл правку.
 *
 * <p>Сервер ничему из окна не верит: окно только показывает и просит, правки проверяются заново.
 */
public final class LandScreen extends ScaledScreen {
    static final int W = 600, H = 364, HEADER = 28, BAR = 26, PANEL = 180, TOOL = 15;
    /** Единиц полотна на чанк: не дальше 10 (план 7.3, «не дальше 12») и не ближе 40. */
    private static final double MIN_CELL = 10, MAX_CELL = 40;
    private static final long RESULT_MS = 6000, UNDO_MS = 30_000;
    private static final int SHIELD_TILE = 0xFFD9714F;

    enum Page { GRID, RIGHTS }

    enum Tool { CLAIM, FORCE, RELEASE }

    /** Выделение не теряется при закрытии окна, пока игрок в мире (план 7.3). */
    static final LongOpenHashSet SELECTION = new LongOpenHashSet();
    static String selectionDim = "";
    static Tool tool = Tool.CLAIM;
    private static Page lastPage = Page.GRID;

    private final Kit.Hits hits = new Kit.Hits();
    private final Kit.Dropdown presetDrop = new Kit.Dropdown();
    private Page page = lastPage;
    private final ResourceKey<Level> dimension;
    private final String dim;
    private double centerX, centerZ, cell = 16;
    private int wx, wy, ww, wh, cx0, cy0, cw, ch, px0;
    private boolean selecting, removing, panning, dragged;
    private long dragFrom, dragTo;
    private @Nullable List<String> tip;
    private String note = "";
    private long noteAt;
    /** Права до первой правки за последние 30 секунд и когда кончается «Вернуть». */
    private int undoBits = -1;
    private long undoUntil;
    /** Таблица, которую окно уже попросило: показывается, пока сервер не прислал новый {@code Hello}. */
    private int askedBits = -1;
    private ClaimsNet.@Nullable Hello askedOver;

    private LandScreen(ResourceKey<Level> dimension, double x, double z) {
        super(Component.translatable("rysclaims.screen.title"));
        this.dimension = dimension;
        this.dim = dimension.location().toString();
        this.centerX = x;
        this.centerZ = z;
        if (!dim.equals(selectionDim)) {
            SELECTION.clear();
            selectionDim = dim;
        }
    }

    /** Открыть на игроке (клавиша, кнопка панели). */
    public static void open() {
        LocalPlayer p = Minecraft.getInstance().player;
        if (p == null) return;
        Minecraft.getInstance().setScreen(new LandScreen(p.level().dimension(), p.getX(), p.getZ()));
    }

    /** Открыть на чанке (меню карты «Занять чанк»): чанк выделен, инструмент «Занять». */
    public static void open(ResourceKey<Level> dimension, int chunkX, int chunkZ, boolean select) {
        LandScreen s = new LandScreen(dimension, chunkX * 16 + 8, chunkZ * 16 + 8);
        s.page = Page.GRID;
        if (select) {
            SELECTION.clear();
            SELECTION.add(Chunks.pack(chunkX, chunkZ));
            tool = Tool.CLAIM;
        }
        Minecraft.getInstance().setScreen(s);
    }

    /** Выход из мира: выделение больше не нужно. */
    public static void reset() {
        SELECTION.clear();
        selectionDim = "";
        tool = Tool.CLAIM;
        lastPage = Page.GRID;
    }

    @Override
    protected void init() {
        super.init();
        ww = Math.min(W, width - 16);
        wh = Math.min(H, height - 16);
        wx = (width - ww) / 2;
        wy = (height - wh) / 2;
        px0 = wx + ww - PANEL;
        cx0 = wx;
        cy0 = wy + HEADER + BAR;
        cw = px0 - wx;
        ch = wy + wh - cy0;
    }

    static Theme slate() {
        for (Theme t : ThemeManager.all()) if (t.id.equals("ryscore:slate")) return t;
        return ThemeManager.get();
    }

    static int deny(Theme t) {
        return t.c("map.deny");
    }

    // ---- рисование ----

    @Override
    protected void draw(GuiGraphics g, int mx, int my, float partial) {
        hits.clear();
        tip = null;
        enterAction = null;
        Theme t = ThemeManager.get();
        g.fill(0, 0, width, height, t.c("overlay"));
        t.panel(g, wx, wy, ww, wh);
        header(g, t, mx, my);
        ClaimsNet.Hello h = LandCache.hello();
        if (!LandCache.available() || h == null) {
            String text = Words.t(LandCache.available() ? "screen.waiting" : "screen.no_server");
            float w = Draw.labelWidth(text, 12.5f, Weight.REGULAR);
            Draw.label(g, text, wx + (ww - w) / 2f, wy + wh / 2f, 12.5f, Weight.REGULAR, t.c("text.muted"));
            return;
        }
        ClaimsNet.EditResult refused = LandCache.result(RESULT_MS);
        if ((askedOver != null && askedOver != h) || (askedBits >= 0 && refused != null && refused.op() == ClaimsNet.OP_RIGHTS && refused.done() == 0)) {
            askedBits = -1;
            askedOver = null;
        }
        pills(g, t, mx, my);
        if (page == Page.GRID) {
            canvas(g, t, slate(), h, mx, my);
            panel(g, t, h, mx, my);
        } else {
            rights(g, t, h, mx, my);
        }
        if (tip != null && !presetDrop.open) Kit.tooltip(g, t, tip, mx, my);
    }

    private void header(GuiGraphics g, Theme t, int mx, int my) {
        int y = wy;
        Draw.round(g, wx + 8, y + 7, 14, 14, 4, SHIELD_TILE);
        Icons.draw(g, Icons.Shape.SHIELD, wx + 11, y + 10, 8, 0xFFFFFFFF);
        String world = worldName();
        float bx = wx + 8 + 14 + 4.5f;
        String shown = Draw.ellipsize(world, 140, 15, Weight.SEMIBOLD);
        Draw.label(g, shown, bx, y + HEADER / 2f, 15, Weight.SEMIBOLD, t.c("text.primary"));
        int tx = (int) (bx + Draw.labelWidth(shown, 15, Weight.SEMIBOLD) + 9);
        Kit.sectionTab(g, t, hits, tx, y, HEADER, Words.t("screen.tab"), true, mx, my, () -> {});
        Kit.close(g, t, hits, wx + ww - 7 - 18, y + 5, mx, my, this::onClose);
        Kit.divider(g, t, wx, wy + HEADER - 0.5f, ww);
    }

    private static String worldName() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.getSingleplayerServer() != null) return mc.getSingleplayerServer().getWorldData().getLevelName();
        if (mc.getCurrentServer() != null) return mc.getCurrentServer().name;
        return Words.t("screen.title");
    }

    /** Подразделы рядом пилюль под шапкой (как «Карта | Список» в книге). */
    private void pills(GuiGraphics g, Theme t, int mx, int my) {
        int y = wy + HEADER + 6, x = wx + 10;
        String a = Words.t("screen.page.grid"), b = Words.t("screen.page.rights");
        int w = (int) Math.ceil(Draw.labelWidth(a, 12.5f, Weight.MEDIUM) + Draw.labelWidth(b, 12.5f, Weight.MEDIUM)) + 24 + 6;
        if (t.beveled()) Draw.bevel(g, x - 2, y - 2, w, 18, t.c("sidebar.bg"), t.c("panel.bevel.dark"), t.c("panel.bevel.light"), 1, true);
        else Draw.roundBox(g, x - 2, y - 2, w, 18, 5, t.c("sidebar.bg"), t.c("panel.border"));
        x += Kit.pill(g, t, hits, x, y, a, page == Page.GRID, mx, my, () -> setPage(Page.GRID)) + 2;
        Kit.pill(g, t, hits, x, y, b, page == Page.RIGHTS, mx, my, () -> setPage(Page.RIGHTS));
    }

    private void setPage(Page p) {
        page = p;
        lastPage = p;
        presetDrop.close();
    }

    // ---- полотно ----

    double ppb() {
        return cell / 16.0;
    }

    double screenX(double blockX) {
        return cx0 + cw / 2.0 + (blockX - centerX) * ppb();
    }

    double screenY(double blockZ) {
        return cy0 + ch / 2.0 + (blockZ - centerZ) * ppb();
    }

    double blockX(double sx) {
        return centerX + (sx - cx0 - cw / 2.0) / ppb();
    }

    double blockZ(double sy) {
        return centerZ + (sy - cy0 - ch / 2.0) / ppb();
    }

    long chunkAt(double sx, double sy) {
        return Chunks.pack(Math.floorDiv((int) Math.floor(blockX(sx)), 16), Math.floorDiv((int) Math.floor(blockZ(sy)), 16));
    }

    /** Отрезки одной серии для {@link Draw#lines}. */
    private static final class Segs {
        float[] xy = new float[256];
        int[] colors = new int[64];
        int n;

        void add(float x0, float y0, float x1, float y1, int color) {
            if (n * 4 + 4 > xy.length) {
                xy = java.util.Arrays.copyOf(xy, xy.length * 2);
                colors = java.util.Arrays.copyOf(colors, colors.length * 2);
            }
            xy[n * 4] = x0;
            xy[n * 4 + 1] = y0;
            xy[n * 4 + 2] = x1;
            xy[n * 4 + 3] = y1;
            colors[n++] = color;
        }

        void draw(GuiGraphics g, float width) {
            if (n > 0) Draw.lines(g, xy, colors, n, width);
        }
    }

    /** Штриховка клетки: семь косых отрезков внутри квадрата. */
    private static void hatch(Segs s, float x, float y, float side, int color) {
        for (int k = 1; k < 8; k++) {
            float d = side * k / 4f;
            float u1 = Math.max(0, d - side), u2 = Math.min(side, d);
            s.add(x + u1, y + side - (d - u1), x + u2, y + side - (d - u2), color);
        }
    }

    private boolean selected(long chunk, long a, long b) {
        boolean in = SELECTION.contains(chunk);
        if (!selecting || !dragged) return in;
        int x = Chunks.x(chunk), z = Chunks.z(chunk);
        boolean rect = x >= Math.min(Chunks.x(a), Chunks.x(b)) && x <= Math.max(Chunks.x(a), Chunks.x(b))
            && z >= Math.min(Chunks.z(a), Chunks.z(b)) && z <= Math.max(Chunks.z(a), Chunks.z(b));
        return removing ? in && !rect : in || rect;
    }

    private void canvas(GuiGraphics g, Theme t, Theme canvas, ClaimsNet.Hello h, int mx, int my) {
        float r = t.beveled() ? 0 : t.r("panel.radius");
        // левый нижний угол полотна повторяет скругление окна
        int bg = canvas.c("map.bg"), ri = (int) Math.ceil(r);
        Draw.rect(g, cx0 + 1, cy0, cw - 1, ch - 1 - ri, bg);
        Draw.rect(g, cx0 + 1 + ri, cy0 + ch - 1 - ri, cw - 1 - ri, ri, bg);
        if (ri > 0) Draw.round(g, cx0 + 1, cy0 + ch - 1 - 2 * r, 2 * r, 2 * r, Math.max(0, r - 1), bg);
        boolean tiles = MapApi.get().present() && MapApi.get().drawTiles(g, dimension, centerX, centerZ, ppb(), cx0 + 1, cy0, cw - 1, ch - 1);
        // в режиме привата карта приглушена (план 7.3)
        if (tiles) Draw.rect(g, cx0 + 1, cy0, cw - 1, ch - 1, Draw.alpha(canvas.c("map.bg"), 0.35f));
        Draw.scissor(g, cx0 + 1, cy0, cx0 + cw, cy0 + ch - 1);

        int chx0 = Math.floorDiv((int) Math.floor(blockX(cx0)), 16), chx1 = Math.floorDiv((int) Math.floor(blockX(cx0 + cw)), 16);
        int chz0 = Math.floorDiv((int) Math.floor(blockZ(cy0)), 16), chz1 = Math.floorDiv((int) Math.floor(blockZ(cy0 + ch)), 16);
        for (int rz = chz0 >> 5; rz <= chz1 >> 5; rz++) for (int rx = chx0 >> 5; rx <= chx1 >> 5; rx++) LandCache.want(dim, rx, rz);

        float side = (float) cell;
        // сетка чанков
        Segs grid = new Segs();
        int gridColor = canvas.c("map.grid");
        for (int cx = chx0; cx <= chx1 + 1; cx++) {
            float x = (float) screenX(cx * 16);
            grid.add(x, cy0, x, cy0 + ch, gridColor);
        }
        for (int cz = chz0; cz <= chz1 + 1; cz++) {
            float y = (float) screenY(cz * 16);
            grid.add(cx0, y, cx0 + cw, y, gridColor);
        }
        grid.draw(g, 0.5f);

        Segs hatches = new Segs(), borders = new Segs(), draft = new Segs();
        int foreign = canvas.c("text.muted"), denyC = deny(canvas), draftC = canvas.c("map.draft");
        Map<UUID, long[]> labels = new HashMap<>();
        List<float[]> anchors = new ArrayList<>();
        long a = dragFrom, b = dragTo;
        for (int cz = chz0; cz <= chz1; cz++) {
            for (int cx = chx0; cx <= chx1; cx++) {
                float x = (float) screenX(cx * 16), y = (float) screenY(cz * 16);
                long chunk = Chunks.pack(cx, cz);
                LandCache.ChunkState st = LandCache.at(dim, cx, cz);
                if (st != null) {
                    boolean own = st.team().id().equals(h.team());
                    int col = own ? st.team().color() : foreign;
                    Draw.round(g, x, y, side, side, 0, Draw.alpha(col, own ? (st.releasing() ? 0.12f : 0.3f) : 0.1f));
                    if (!own) hatch(hatches, x, y, side, Draw.alpha(foreign, 0.55f));
                    if (st.releasing()) hatch(hatches, x, y, side, Draw.alpha(denyC, 0.8f));
                    if (own && st.force()) anchors.add(new float[] {x, y});
                    edge(borders, st, LandCache.at(dim, cx, cz - 1), x, y, x + side, y, col);
                    edge(borders, st, LandCache.at(dim, cx, cz + 1), x, y + side, x + side, y + side, col);
                    edge(borders, st, LandCache.at(dim, cx - 1, cz), x, y, x, y + side, col);
                    edge(borders, st, LandCache.at(dim, cx + 1, cz), x + side, y, x + side, y + side, col);
                    labels.putIfAbsent(st.team().id(), new long[] {chunk});
                }
                if (selected(chunk, a, b)) {
                    Draw.round(g, x, y, side, side, 0, Draw.alpha(draftC, 0.25f));
                    if (!selected(Chunks.pack(cx, cz - 1), a, b)) draft.add(x, y, x + side, y, draftC);
                    if (!selected(Chunks.pack(cx, cz + 1), a, b)) draft.add(x, y + side, x + side, y + side, draftC);
                    if (!selected(Chunks.pack(cx - 1, cz), a, b)) draft.add(x, y, x, y + side, draftC);
                    if (!selected(Chunks.pack(cx + 1, cz), a, b)) draft.add(x + side, y, x + side, y + side, draftC);
                }
            }
        }
        hatches.draw(g, 0.75f);
        borders.draw(g, 1f);
        draft.draw(g, 1.25f);
        float as = Math.min(8, side * 0.5f);
        for (float[] p : anchors) Icons.draw(g, Icons.Shape.ANCHOR, p[0] + (side - as) / 2f, p[1] + (side - as) / 2f, as, 0xFFFFFFFF);
        for (Map.Entry<UUID, long[]> e : labels.entrySet()) label(g, canvas, e.getKey(), e.getValue()[0], h);

        LocalPlayer player = Minecraft.getInstance().player;
        if (player != null && player.level().dimension().equals(dimension)) {
            float sx = (float) screenX(player.getX()), sy = (float) screenY(player.getZ());
            Icons.drawRotated(g, Icons.Shape.NAV, sx - 5, sy - 5, 10, player.getYRot() + 180, 0xFFFFFFFF);
        }
        g.disableScissor();

        tools(g, t, h, mx, my);
        boolean over = Kit.over(mx, my, cx0, cy0, cw, ch) && !overTools(mx, my);
        if (over && !selecting && !panning) {
            long c = chunkAt(mx, my);
            List<Component> lines = LandLayer.lines(dim, Chunks.x(c), Chunks.z(c), false);
            if (lines != null) {
                List<String> out = new ArrayList<>();
                for (Component line : lines) out.add(line.getString());
                tip = out;
            }
        }
    }

    /** Край клетки, если сосед не той же команды. */
    private static void edge(Segs s, LandCache.ChunkState me, LandCache.@Nullable ChunkState other, float x0, float y0, float x1, float y1, int color) {
        if (other != null && other.team().id().equals(me.team().id())) return;
        s.add(x0, y0, x1, y1, color);
    }

    /** Подпись команды над её первой видимой клеткой: точка цвета и имя. */
    private void label(GuiGraphics g, Theme canvas, UUID team, long chunk, ClaimsNet.Hello h) {
        LandCache.TeamInfo info = LandCache.team(team);
        if (info == null) return;
        String name = Draw.ellipsize(info.name(), 90, 11.5f, Weight.SEMIBOLD);
        float tw = Draw.labelWidth(name, 11.5f, Weight.SEMIBOLD);
        float w = 4 + 4 + 3 + tw + 4, hh = 9;
        float x = (float) screenX(Chunks.x(chunk) * 16);
        float y = (float) screenY(Chunks.z(chunk) * 16) - hh - 1;
        if (y < cy0 + 1) y = cy0 + 1;
        Draw.round(g, x, y, w, hh, 2.5f, canvas.c("map.label.bg"));
        Draw.round(g, x + 4, y + 2.5f, 4, 4, 1, team.equals(h.team()) ? info.color() : canvas.c("text.muted"));
        Draw.label(g, name, x + 11, y + hh / 2f, 11.5f, Weight.SEMIBOLD, canvas.c("map.label.fg"));
    }

    /** Ширина нижней строки инструментов полотна в прошлом кадре: «Ко мне» и номер чанка. */
    private int bottomW = 70;

    private boolean overTools(int mx, int my) {
        int right = cx0 + cw;
        return Kit.over(mx, my, right - 6 - TOOL * 2 - 3, cy0 + 6, TOOL * 2 + 3, TOOL)
            || Kit.over(mx, my, cx0 + 6, cy0 + ch - 6 - TOOL, bottomW, TOOL)
            || (LandCache.hello() != null && LandCache.hello().releasing() > 0 && Kit.over(mx, my, cx0 + 6, cy0 + ch - 6 - TOOL * 2 - 4, cw - 12, TOOL + 2));
    }

    private void tools(GuiGraphics g, Theme t, ClaimsNet.Hello h, int mx, int my) {
        int right = cx0 + cw;
        int y = cy0 + 6, x = right - 6 - TOOL;
        square(g, t, x, y, Icons.Shape.PLUS, mx, my, () -> zoomAt(cx0 + cw / 2.0, cy0 + ch / 2.0, 1));
        x -= TOOL + 3;
        square(g, t, x, y, Icons.Shape.MINUS, mx, my, () -> zoomAt(cx0 + cw / 2.0, cy0 + ch / 2.0, -1));

        int by = cy0 + ch - 6 - TOOL;
        int bw = toolButton(g, t, cx0 + 6, by, Icons.Shape.TARGET, Words.t("screen.to_me"), Words.t("screen.key.space"), mx, my, this::toPlayer);
        bottomW = bw;
        if (Kit.over(mx, my, cx0, cy0, cw, ch)) {
            long c = chunkAt(mx, my);
            int cx = Chunks.x(c), cz = Chunks.z(c);
            String text = Words.t("screen.chunk_at", cx, cz);
            String blocks = Words.t("screen.blocks_at", cx * 16, cx * 16 + 15, cz * 16, cz * 16 + 15);
            float w1 = Draw.labelWidth(text, 12, Weight.REGULAR), w2 = Draw.labelWidth(blocks, 11.5f, Weight.REGULAR);
            float w = 5 + w1 + 5 + 1 + 5 + w2 + 5;
            int px = cx0 + 6 + bw + 3;
            if (px + w > right - 6) w = 5 + w1 + 5;
            pill(g, t, px, by, w, TOOL);
            bottomW = (int) Math.ceil(bw + 3 + w);
            Draw.label(g, text, px + 5, by + TOOL / 2f, 12, Weight.REGULAR, t.c("text.secondary"));
            if (w > 5 + w1 + 5) {
                Draw.rect(g, (int) (px + 5 + w1 + 5), by + 3, 1, TOOL - 6, t.c("panel.border"));
                Draw.label(g, blocks, px + 5 + w1 + 11, by + TOOL / 2f, 11.5f, Weight.REGULAR, t.c("text.muted"));
            }
        }

        // освобождение идёт: строка с отсчётом и «Оставить» (макет 33), окна-вопроса нет
        if (h.releasing() > 0) {
            int sy = by - TOOL - 6, sx = cx0 + 6, sw = cw - 12, sh = TOOL + 2;
            if (t.beveled()) Draw.bevel(g, sx, sy, sw, sh, t.c("sidebar.bg"), t.c("panel.bevel.dark"), t.c("panel.bevel.light"), 1, false);
            else Draw.roundBox(g, sx, sy, sw, sh, 4, t.c("sidebar.bg"), t.c("panel.border"));
            Icons.draw(g, Icons.Shape.WARN, sx + 6, sy + (sh - 8) / 2f, 8, t.c("state.warn"));
            long left = (LandCache.releaseLeft() + 999) / 1000;
            String text = Words.t("screen.releasing", Words.chunks(h.releasing()), left);
            String keep = Words.t("screen.keep");
            String key = Words.t("screen.key.undo");
            float kw = Draw.labelWidth(keep, 12, Weight.MEDIUM) + 3 + Draw.labelWidth(key, 11, Weight.REGULAR) + 5 + 12;
            Draw.label(g, Draw.ellipsize(text, sw - 24 - kw - 8, 12, Weight.REGULAR), sx + 18, sy + sh / 2f, 12, Weight.REGULAR, t.c("text.primary"));
            toolButton(g, t, (int) (sx + sw - 3 - kw), sy + 1, null, keep, key, mx, my, this::keep);
        }
    }

    private boolean square(GuiGraphics g, Theme t, int x, int y, Icons.Shape icon, int mx, int my, Runnable action) {
        boolean hot = Kit.over(mx, my, x, y, TOOL, TOOL);
        if (t.beveled()) {
            t.button(g, x, y, TOOL, TOOL, hot, false);
        } else {
            int fill = t.c("sidebar.bg");
            if (hot) fill = Draw.mix(fill, t.c("text.primary"), 0.08f);
            Draw.roundBox(g, x, y, TOOL, TOOL, Math.min(t.r("button.radius"), TOOL / 2f), fill, t.c("button.border"));
        }
        Icons.draw(g, icon, x + 3.5f, y + 3.5f, 8, hot ? t.c("text.primary") : t.c("text.secondary"));
        hits.add(x, y, TOOL, TOOL, b -> {
            if (b == 0) action.run();
        });
        return hot;
    }

    /** Кнопка с значком, подписью и клавишей в рамке; ширина по содержимому. */
    private int toolButton(GuiGraphics g, Theme t, int x, int y, @Nullable Icons.Shape icon, String label, @Nullable String key, int mx, int my, Runnable action) {
        float iconW = icon == null ? 0 : 7 + 3;
        float keyW = key == null ? 0 : Draw.labelWidth(key, 11, Weight.REGULAR) + 5 + 3;
        float textW = Draw.labelWidth(label, 12, Weight.MEDIUM);
        int w = (int) Math.ceil(5 + iconW + textW + keyW + 5), h = TOOL;
        boolean hot = Kit.over(mx, my, x, y, w, h);
        if (t.beveled()) {
            t.button(g, x, y, w, h, hot, false);
        } else {
            int fill = t.c("sidebar.bg");
            if (hot) fill = Draw.mix(fill, t.c("text.primary"), 0.08f);
            Draw.roundBox(g, x, y, w, h, Math.min(t.r("button.radius"), h / 2f), fill, t.c("button.border"));
        }
        float cx = x + 5;
        int fg = hot ? t.c("text.primary") : t.c("text.secondary");
        if (icon != null) {
            Icons.draw(g, icon, cx, y + (h - 7) / 2f, 7, fg);
            cx += iconW;
        }
        Draw.label(g, label, cx, y + h / 2f, 12, Weight.MEDIUM, fg);
        cx += textW + 3;
        if (key != null) kbd(g, t, key, cx, y + h / 2f);
        hits.add(x, y, w, h, b -> {
            if (b == 0) action.run();
        });
        return w;
    }

    private static float kbd(GuiGraphics g, Theme t, String key, float x, float cy) {
        float w = Draw.labelWidth(key, 11, Weight.REGULAR) + 5;
        if (t.beveled()) Draw.rect(g, (int) x, (int) (cy - 4), (int) w, 8, t.c("button.bg"));
        else Draw.roundBox(g, x, cy - 4, w, 8, 2.5f, t.c("button.bg"), t.c("button.border"));
        Draw.label(g, key, x + 2.5f, cy, 11, Weight.REGULAR, t.c("text.muted"));
        return w;
    }

    private static void pill(GuiGraphics g, Theme t, float x, float y, float w, float h) {
        if (t.beveled()) Draw.bevel(g, x, y, w, h, t.c("sidebar.bg"), t.c("panel.bevel.dark"), t.c("panel.bevel.light"), 1, false);
        else Draw.roundBox(g, x, y, w, h, Math.min(t.r("button.radius"), h / 2f), t.c("sidebar.bg"), t.c("panel.border"));
    }

    // ---- панель ----

    /** Что в выделении: свободные, свои по видам, чужие. */
    private static final class Sel {
        int total, free, foreign, own, ownForced, ownUnforced, ownReleasing;
        int minX = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        final LongArrayList freeList = new LongArrayList(), forceOn = new LongArrayList(), forceOff = new LongArrayList(), release = new LongArrayList();
    }

    private Sel analyse(ClaimsNet.Hello h) {
        Sel s = new Sel();
        for (long c : SELECTION) {
            s.total++;
            int x = Chunks.x(c), z = Chunks.z(c);
            s.minX = Math.min(s.minX, x);
            s.maxX = Math.max(s.maxX, x);
            s.minZ = Math.min(s.minZ, z);
            s.maxZ = Math.max(s.maxZ, z);
            LandCache.ChunkState st = LandCache.at(dim, x, z);
            if (st == null) {
                s.free++;
                s.freeList.add(c);
            } else if (!st.team().id().equals(h.team())) {
                s.foreign++;
            } else {
                s.own++;
                if (st.releasing()) s.ownReleasing++;
                else s.release.add(c);
                if (st.force()) {
                    s.ownForced++;
                    s.forceOff.add(c);
                } else if (!st.releasing()) {
                    s.ownUnforced++;
                    s.forceOn.add(c);
                }
            }
        }
        // ближние к середине выделения первыми: при нехватке лимита сервер займёт их
        if (s.total > 0) {
            double mxc = (s.minX + s.maxX) / 2.0, mzc = (s.minZ + s.maxZ) / 2.0;
            s.freeList.sort((p, q) -> Double.compare(d2(p, mxc, mzc), d2(q, mxc, mzc)));
        }
        return s;
    }

    private static double d2(long c, double x, double z) {
        double dx = Chunks.x(c) - x, dz = Chunks.z(c) - z;
        return dx * dx + dz * dz;
    }

    private void panel(GuiGraphics g, Theme t, ClaimsNet.Hello h, int mx, int my) {
        int top = wy + HEADER, bottom = wy + wh, x = px0 + 9, w = PANEL - 18;
        Draw.rect(g, px0, top, 1, bottom - top - 1, t.c("panel.border"));
        Sel s = analyse(h);
        boolean right = switch (tool) {
            case CLAIM, RELEASE -> h.canClaim();
            case FORCE -> h.canForce();
        };

        // команда и роль
        int y = top + 9;
        Draw.round(g, x, y, 22, 22, 5, h.color());
        Icons.draw(g, Icons.Shape.SHIELD, x + 5, y + 5, 12, 0xFFFFFFFF);
        String title = h.solo() ? Words.t("panel.title.solo") : Words.t("panel.title", h.name());
        Draw.label(g, Draw.ellipsize(title, w - 28, 13.5f, Weight.SEMIBOLD), x + 28, y + 5, 13.5f, Weight.SEMIBOLD, t.c("text.primary"));
        if (title.length() > 0 && Draw.labelWidth(title, 13.5f, Weight.SEMIBOLD) > w - 28 && Kit.over(mx, my, x + 28, y, w - 28, 10)) tip = List.of(title);
        String role = h.solo() ? Words.t("panel.role.solo")
            : Words.t("panel.role", Words.role(h.role()), Words.t(h.canClaim() ? "panel.role.can" : "panel.role.cannot"));
        float ly = y + 14;
        for (String line : Draw.wrapLabel(role, w - 28, 11, Weight.REGULAR, 2)) {
            Draw.label(g, line, x + 28, ly, 11, Weight.REGULAR, t.c("text.muted"));
            ly += 7;
        }
        y += 32;

        // лимиты
        int addClaims = tool == Tool.CLAIM ? s.free : 0, relClaims = tool == Tool.RELEASE ? s.release.size() : 0;
        int addForce = tool == Tool.FORCE ? s.ownUnforced : 0, relForce = tool == Tool.FORCE ? s.ownForced : 0;
        if (tool == Tool.RELEASE) for (long c : s.release) {
            LandCache.ChunkState st = LandCache.at(dim, Chunks.x(c), Chunks.z(c));
            if (st != null && st.force()) relForce++;
        }
        if (tool == Tool.FORCE && s.ownUnforced > 0 && s.ownForced > 0) relForce = 0;
        limit(g, t, x, y, w, Icons.Shape.SHIELD, Words.t("panel.claims"), h.claimed(), addClaims, relClaims, h.claimLimit());
        y += 22;
        limit(g, t, x, y, w, Icons.Shape.ANCHOR, Words.t("panel.force"), h.forced(), addForce, relForce, h.forceLimit());
        y += 24;

        // инструменты
        int bw = (w - 6) / 3;
        toolTab(g, t, x, y, bw, Tool.CLAIM, Icons.Shape.SHIELD, Words.t("tool.claim"), h.canClaim(), mx, my);
        toolTab(g, t, x + bw + 3, y, bw, Tool.FORCE, Icons.Shape.ANCHOR, Words.t("tool.force"), h.canForce(), mx, my);
        toolTab(g, t, x + 2 * (bw + 3), y, w - 2 * (bw + 3), Tool.RELEASE, Icons.Shape.CROSS, Words.t("tool.release"), h.canClaim(), mx, my);
        y += 34;

        // итог выделения
        Kit.caps(g, t, Words.t("panel.selected"), x, y + 3, 11, 0.44f);
        if (s.total > 0) {
            String size = s.total == (s.maxX - s.minX + 1) * (s.maxZ - s.minZ + 1)
                ? Words.t("panel.size", s.maxX - s.minX + 1, s.maxZ - s.minZ + 1) : Words.chunks(s.total);
            Draw.label(g, size, x + w - Draw.labelWidth(size, 11, Weight.REGULAR), y + 3, 11, Weight.REGULAR, t.c("text.muted"));
        }
        y += 10;
        List<String[]> rows = new ArrayList<>();
        int warnRow = -1;
        if (!right) {
            y = callout(g, t, x, y, w, Words.t(h.solo() ? "panel.no_right.solo" : "panel.no_right"));
        } else if (s.total == 0) {
            y = callout(g, t, x, y, w, Words.t("panel.hint"));
        } else {
            switch (tool) {
                case CLAIM -> {
                    rows.add(new String[] {Words.t("row.new"), Integer.toString(s.free)});
                    if (s.own > 0) rows.add(new String[] {Words.t("row.own"), Integer.toString(s.own)});
                    if (s.foreign > 0) rows.add(new String[] {Words.t("row.foreign"), Integer.toString(s.foreign)});
                    int over = h.claimed() + s.free - h.claimLimit();
                    if (over > 0 && s.free > 0) {
                        warnRow = rows.size();
                        rows.add(new String[] {Words.t("row.over"), Integer.toString(Math.min(over, s.free))});
                    }
                }
                case FORCE -> {
                    if (s.ownUnforced > 0) rows.add(new String[] {Words.t("row.force_on"), Integer.toString(s.ownUnforced)});
                    if (s.ownForced > 0) rows.add(new String[] {Words.t("row.force_off"), Integer.toString(s.ownForced)});
                    if (s.own == 0) rows.add(new String[] {Words.t("row.none_own"), ""});
                    int left = h.forceLimit() - h.forced() - s.ownUnforced;
                    if (left < 0 && s.ownUnforced > 0) {
                        warnRow = rows.size();
                        rows.add(new String[] {Words.t("row.force_over"), Integer.toString(-left)});
                    } else {
                        rows.add(new String[] {Words.t("row.force_left"), Words.t("row.of", Math.max(0, left), h.forceLimit())});
                    }
                }
                case RELEASE -> {
                    rows.add(new String[] {Words.t("row.release"), Integer.toString(s.release.size())});
                    int forced = 0;
                    for (long c : s.release) {
                        LandCache.ChunkState st = LandCache.at(dim, Chunks.x(c), Chunks.z(c));
                        if (st != null && st.force()) forced++;
                    }
                    if (forced > 0) rows.add(new String[] {Words.t("row.release_forced"), Integer.toString(forced)});
                    if (s.ownReleasing > 0) rows.add(new String[] {Words.t("row.releasing"), Integer.toString(s.ownReleasing)});
                    if (s.total - s.own > 0) rows.add(new String[] {Words.t("row.not_own"), Integer.toString(s.total - s.own)});
                    if (!s.release.isEmpty()) {
                        warnRow = -2;
                    }
                }
            }
            int rh = 16, cardH = rows.size() * rh;
            Draw.roundBox(g, x, y, w, cardH, 5, t.c("card.bg"), t.c("card.border"));
            for (int i = 0; i < rows.size(); i++) {
                float cy = y + i * rh + rh / 2f;
                if (i > 0) Draw.rect(g, x + 6, y + i * rh, w - 12, 1, t.c("card.border"));
                Draw.label(g, rows.get(i)[0], x + 6, cy, 11.5f, Weight.REGULAR, t.c("text.muted"));
                String v = rows.get(i)[1];
                int vc = i == warnRow ? deny(t) : t.c("text.primary");
                Draw.label(g, v, x + w - 6 - Draw.labelWidth(v, 12, Weight.SEMIBOLD), cy, 12, Weight.SEMIBOLD, vc);
            }
            y += cardH + 6;
            if (warnRow == -2) y = callout(g, t, x, y, w, Words.t("panel.release_note", h.releaseSeconds()));
        }

        // итог последней правки
        ClaimsNet.EditResult res = LandCache.result(RESULT_MS);
        String last = !note.isEmpty() && System.currentTimeMillis() - noteAt < RESULT_MS ? note : res != null ? Words.result(res) : "";
        if (!last.isEmpty()) {
            boolean bad = note.isEmpty() && res != null && res.done() == 0;
            for (String line : Draw.wrapLabel(last, w, 11, Weight.REGULAR, 3)) {
                Draw.label(g, line, x, y + 4, 11, Weight.REGULAR, bad ? deny(t) : t.c("text.muted"));
                y += 7;
            }
        }

        // легенда
        int footer = bottom - 30;
        Theme canvas = slate();
        int lyy = footer - 22;
        legend(g, t, x, lyy, Draw.alpha(h.color(), 0.6f), false, Words.t("legend.own"));
        legend(g, t, x + w / 2, lyy, canvas.c("text.muted"), true, Words.t("legend.other"));
        Icons.draw(g, Icons.Shape.ANCHOR, x, lyy + 9.5f, 6, t.c("text.muted"));
        Draw.label(g, Words.t("legend.force"), x + 9, lyy + 12.5f, 11, Weight.REGULAR, t.c("text.muted"));
        legend(g, t, x + w / 2, lyy + 9, Draw.alpha(canvas.c("map.draft"), 0.6f), false, Words.t("legend.selected"));

        // низ: главная кнопка и «Сбросить»
        Draw.rect(g, px0 + 1, footer, PANEL - 2, 1, t.c("panel.border"));
        footer(g, t, h, s, right, x, footer + 7, w, mx, my);
    }

    private int callout(GuiGraphics g, Theme t, int x, int y, int w, String text) {
        List<String> lines = Draw.wrapLabel(text, w - 12, 11.5f, Weight.REGULAR, 6);
        int hgt = lines.size() * 8 + 8;
        Draw.roundBox(g, x, y, w, hgt, 5, t.c("card.bg"), t.c("card.border"));
        float ly = y + 8;
        for (String line : lines) {
            Draw.label(g, line, x + 6, ly, 11.5f, Weight.REGULAR, t.c("text.secondary"));
            ly += 8;
        }
        return y + hgt + 6;
    }

    private static void legend(GuiGraphics g, Theme t, int x, int y, int color, boolean hatch, String text) {
        Draw.round(g, x, y + 1, 6, 6, 1.5f, color);
        if (hatch) Draw.line(g, x + 0.5f, y + 6.5f, x + 5.5f, y + 1.5f, 0.75f, t.c("panel.bg"));
        Draw.label(g, text, x + 9, y + 4, 11, Weight.REGULAR, t.c("text.muted"));
    }

    /** Строка лимита: значок, подпись, «110 +12 из 500» и полоса; прибавка цветом выделения, убыль и нехватка красным. */
    private void limit(GuiGraphics g, Theme t, int x, int y, int w, Icons.Shape icon, String label, int used, int add, int minus, int limit) {
        Icons.draw(g, icon, x, y + 1, 7, t.c("text.muted"));
        Draw.label(g, label, x + 10, y + 4.5f, 12, Weight.REGULAR, t.c("text.secondary"));
        String of = " " + Words.t("panel.of", limit);
        String delta = add > 0 ? " +" + add : minus > 0 ? " −" + minus : "";
        String main = Integer.toString(used);
        float wOf = Draw.labelWidth(of, 11.5f, Weight.REGULAR), wD = Draw.labelWidth(delta, 12, Weight.SEMIBOLD), wM = Draw.labelWidth(main, 12, Weight.SEMIBOLD);
        float rx = x + w - wOf;
        Draw.label(g, of, rx, y + 4.5f, 11.5f, Weight.REGULAR, t.c("text.muted"));
        boolean over = used + add > limit;
        if (!delta.isEmpty()) Draw.label(g, delta, rx - wD, y + 4.5f, 12, Weight.SEMIBOLD, add > 0 && !over ? t.c("text.primary") : deny(t));
        Draw.label(g, main, rx - wD - wM, y + 4.5f, 12, Weight.SEMIBOLD, t.c("text.primary"));
        int by = y + 10;
        Draw.round(g, x, by, w, 3, 1.5f, t.c("slot.bg"));
        if (limit <= 0) return;
        float k = w / (float) limit;
        float uw = Math.min(w, used * k);
        Draw.round(g, x, by, uw, 3, 1.5f, over && add == 0 ? deny(t) : t.c("text.secondary"));
        if (add > 0) {
            float aw = Math.min(w - uw, add * k);
            if (aw > 0) Draw.round(g, x + uw, by, aw, 3, 1.5f, over ? deny(t) : slate().c("map.draft"));
        } else if (minus > 0) {
            float mw = Math.min(uw, minus * k);
            Draw.round(g, x + uw - mw, by, mw, 3, 1.5f, deny(t));
        }
    }

    private void toolTab(GuiGraphics g, Theme t, int x, int y, int w, Tool which, Icons.Shape icon, String label, boolean enabled, int mx, int my) {
        int h = 26;
        boolean active = tool == which, hot = Kit.over(mx, my, x, y, w, h);
        if (t.beveled()) {
            if (active) t.pressed(g, x, y, w, h, 0);
            else t.button(g, x, y, w, h, hot, false);
        } else {
            int fill = active ? t.c("tab.active.bg") : t.c("button.bg");
            if (hot && !active) fill = Draw.mix(fill, t.c("text.primary"), 0.06f);
            Draw.roundBox(g, x, y, w, h, 5, fill, active ? t.c("text.muted") : t.c("button.border"));
        }
        int fg = !enabled ? t.c("text.dim") : active || hot ? t.c("text.primary") : t.c("text.secondary");
        Icons.draw(g, icon, x + (w - 8) / 2f, y + 5, 8, fg);
        String text = Draw.ellipsize(label, w - 4, 11.5f, active ? Weight.SEMIBOLD : Weight.REGULAR);
        float tw = Draw.labelWidth(text, 11.5f, active ? Weight.SEMIBOLD : Weight.REGULAR);
        Draw.label(g, text, x + (w - tw) / 2f, y + 19, 11.5f, active ? Weight.SEMIBOLD : Weight.REGULAR, fg);
        hits.add(x, y, w, h, b -> {
            if (b == 0) tool = which;
        });
    }

    private void footer(GuiGraphics g, Theme t, ClaimsNet.Hello h, Sel s, boolean right, int x, int y, int w, int mx, int my) {
        int bh = 17;
        String reset = Words.t("panel.reset");
        int rw = (int) Math.ceil(Draw.labelWidth(reset, 12, Weight.MEDIUM)) + 10;
        if (!SELECTION.isEmpty()) {
            boolean hot = Kit.over(mx, my, x + w - rw, y, rw, bh);
            Draw.label(g, reset, x + w - rw + 5, y + bh / 2f, 12, Weight.MEDIUM, hot ? t.c("text.primary") : t.c("text.secondary"));
            hits.add(x + w - rw, y, rw, bh, b -> SELECTION.clear());
        }
        int room = w - (SELECTION.isEmpty() ? 0 : rw + 4);
        if (!right || s.total == 0) {
            disabled(g, t, x, y, room, bh, Words.t(s.total == 0 ? "action.none" : "action.no_right"));
            return;
        }
        switch (tool) {
            case CLAIM -> {
                if (s.free == 0) disabled(g, t, x, y, room, bh, Words.t("button.nothing"));
                else main(g, t, x, y, room, bh, s.foreign > 0 || s.own > 0 ? Words.t("button.claim_of", s.free, s.total) : Words.t("button.claim", Words.chunks(s.free)),
                    mx, my, () -> send(ClaimsNet.OP_CLAIM, s.freeList));
            }
            case FORCE -> {
                if (s.forceOn.isEmpty() && s.forceOff.isEmpty()) disabled(g, t, x, y, room, bh, Words.t("button.nothing"));
                else if (!s.forceOn.isEmpty() && !s.forceOff.isEmpty()) {
                    String off = Words.t("button.unload", s.forceOff.size());
                    int ow = (int) Kit.buttonWidth(off, 12);
                    Kit.button(g, t, hits, x, y, ow, bh, off, false, mx, my, () -> send(ClaimsNet.OP_UNLOAD, s.forceOff));
                    main(g, t, x + ow + 3, y, room - ow - 3, bh, Words.t("button.load", s.forceOn.size()), mx, my, () -> send(ClaimsNet.OP_LOAD, s.forceOn));
                } else if (!s.forceOn.isEmpty()) {
                    main(g, t, x, y, room, bh, Words.t("button.load", s.forceOn.size()), mx, my, () -> send(ClaimsNet.OP_LOAD, s.forceOn));
                } else {
                    main(g, t, x, y, room, bh, Words.t("button.unload", s.forceOff.size()), mx, my, () -> send(ClaimsNet.OP_UNLOAD, s.forceOff));
                }
            }
            case RELEASE -> {
                if (s.release.isEmpty()) disabled(g, t, x, y, room, bh, Words.t("button.nothing"));
                else main(g, t, x, y, room, bh, Words.t("button.release", Words.chunks(s.release.size())), mx, my, () -> send(ClaimsNet.OP_RELEASE, s.release));
            }
        }
    }

    /** Главная кнопка окна: янтарная, подпись и клавиша Enter. */
    private void main(GuiGraphics g, Theme t, int x, int y, int w, int h, String label, int mx, int my, Runnable action) {
        String key = Words.t("screen.key.enter");
        float kw = Draw.labelWidth(key, 11, Weight.REGULAR) + 5;
        String text = Draw.ellipsize(label, w - kw - 14, 13, Weight.SEMIBOLD);
        Kit.button(g, t, hits, x, y, w, h, "", true, mx, my, action);
        float tw = Draw.labelWidth(text, 13, Weight.SEMIBOLD);
        float sx = x + (w - tw - 4 - kw) / 2f;
        Draw.label(g, text, sx, y + h / 2f, 13, Weight.SEMIBOLD, t.c("accent.text"));
        float kx = sx + tw + 4;
        Draw.roundBox(g, kx, y + h / 2f - 4, kw, 8, 2.5f, Draw.alpha(t.c("accent.text"), 0.12f), Draw.alpha(t.c("accent.text"), 0.3f));
        Draw.label(g, key, kx + 2.5f, y + h / 2f, 11, Weight.REGULAR, t.c("accent.text"));
        enterAction = action;
    }

    private @Nullable Runnable enterAction;

    private static void disabled(GuiGraphics g, Theme t, int x, int y, int w, int h, String label) {
        Draw.roundBox(g, x, y, w, h, 4.5f, Draw.alpha(t.c("button.bg"), 0.5f), t.c("button.border"));
        String text = Draw.ellipsize(label, w - 8, 12.5f, Weight.SEMIBOLD);
        float tw = Draw.labelWidth(text, 12.5f, Weight.SEMIBOLD);
        Draw.label(g, text, x + (w - tw) / 2f, y + h / 2f, 12.5f, Weight.SEMIBOLD, t.c("text.dim"));
    }

    private void send(int op, LongArrayList chunks) {
        if (chunks.isEmpty()) return;
        LandCache.clearResult();
        note = "";
        LandCache.edit(dim, op, new LongArrayList(chunks));
        SELECTION.clear();
    }

    private void keep() {
        LandCache.clearResult();
        note = "";
        LandCache.send(ClaimsNet.Keep.INSTANCE);
    }

    // ---- права ----

    private void rights(GuiGraphics g, Theme t, ClaimsNet.Hello h, int mx, int my) {
        int bits = askedBits >= 0 ? askedBits : h.rights();
        Rights r = Rights.fromBits(bits);
        boolean edit = h.canRights();
        int x = wx + 14, top = wy + HEADER + BAR + 4;

        // набор справа в строке пилюль
        Rights.Preset preset = r.preset();
        String label = preset == null ? Words.t("set.custom") : presetName(preset, h.solo());
        int dropW = Kit.dropdownWidth(label, 130);
        int dx = wx + ww - 14 - dropW;
        String setLabel = Words.t("rights.preset");
        Draw.label(g, setLabel, dx - 6 - Draw.labelWidth(setLabel, 12, Weight.REGULAR), wy + HEADER + 13, 12, Weight.REGULAR, t.c("text.muted"));
        if (edit) Kit.dropdown(g, t, hits, presetDrop, dx, wy + HEADER + 6, dropW, label, false, mx, my);
        else Draw.label(g, label, dx + 5, wy + HEADER + 13, 12.5f, Weight.REGULAR, t.c("text.secondary"));

        // таблица
        int nameW = 250, colW = 72, rowH = 21;
        int tableW = nameW + colW * 3;
        String[][] cols = h.solo()
            ? new String[][] {{Words.t("col.you"), playerName()}, {Words.t("col.trusted"), Words.plural(h.trusted(), "players")}, {Words.t("col.all"), Words.t("col.all.note")}}
            : new String[][] {{Words.t("col.team"), Words.plural(h.members(), "players")}, {Words.t("col.allies"), Words.t("col.allies.note")}, {Words.t("col.all"), Words.t("col.all.note")}};
        for (int i = 0; i < 3; i++) {
            float ccx = x + nameW + colW * i + colW / 2f;
            float tw = Draw.labelWidth(cols[i][0], 12.5f, Weight.SEMIBOLD);
            Draw.label(g, cols[i][0], ccx - tw / 2f, top + 6, 12.5f, Weight.SEMIBOLD, t.c("text.primary"));
            String sub = Draw.ellipsize(cols[i][1], colW - 4, 11, Weight.REGULAR);
            float sw = Draw.labelWidth(sub, 11, Weight.REGULAR);
            Draw.label(g, sub, ccx - sw / 2f, top + 13.5f, 11, Weight.REGULAR, t.c("text.muted"));
        }
        int y = top + 20;
        Rights.Action[] actions = Rights.Action.values();
        Icons.Shape[] icons = {Icons.Shape.PICK, Icons.Shape.ARCHIVE, Icons.Shape.KEY, Icons.Shape.HAND};
        for (int i = 0; i < actions.length; i++) {
            Rights.Action a = actions[i];
            Kit.divider(g, t, x, y, tableW);
            Draw.roundBox(g, x + 1, y + 4, 13, 13, 3, t.c("slot.bg"), t.c("card.border"));
            Icons.draw(g, icons[i], x + 3.5f, y + 6.5f, 8, t.c("text.secondary"));
            Draw.label(g, Words.t("right." + a.key()), x + 20, y + 7, 12.5f, Weight.REGULAR, t.c("text.primary"));
            Draw.label(g, Draw.ellipsize(Words.t("right." + a.key() + ".note"), nameW - 24, 11, Weight.REGULAR), x + 20, y + 14.5f, 11, Weight.REGULAR, t.c("text.muted"));
            for (int c = 0; c < 3; c++) {
                Rights.Column col = Rights.Column.values()[c];
                int bx = (int) (x + nameW + colW * c + colW / 2f - 4.5f), by = y + 6;
                boolean on = r.shown(a, col);
                // у одиночки первый столбец это он сам (лидер): ему можно всё
                Runnable toggle = !edit || (h.solo() && col == Rights.Column.TEAM) ? null : () -> cell(h, a, col, !on);
                Kit.checkbox(g, t, hits, bx, by, h.solo() && col == Rights.Column.TEAM || on, mx, my, toggle);
            }
            y += rowH;
        }
        Kit.divider(g, t, x, y, tableW);
        y += 8;

        // пояснения
        List<String> notes = new ArrayList<>();
        notes.add(Words.t(h.solo() ? "rights.note.solo" : "rights.note.leader"));
        if (!edit) notes.add(Words.t(h.rightsLocked() ? "rights.locked" : "rights.no_right"));
        notes.add(Words.t("rights.note.more"));
        for (String n : notes) {
            for (String line : Draw.wrapLabel(n, tableW, 11.5f, Weight.REGULAR, 3)) {
                Draw.label(g, line, x, y + 4, 11.5f, Weight.REGULAR, t.c("text.muted"));
                y += 8;
            }
            y += 3;
        }
        ClaimsNet.EditResult res = LandCache.result(RESULT_MS);
        if (res != null && res.op() == ClaimsNet.OP_RIGHTS && res.done() == 0) {
            Draw.label(g, Words.result(res), x, y + 4, 11.5f, Weight.REGULAR, deny(t));
        }

        // «Права изменены · Вернуть · 26 с» (макет 41)
        long left = undoUntil - System.currentTimeMillis();
        if (undoBits >= 0 && left > 0) {
            int sy = wy + wh - 10 - 20, sx = wx + 10, sw = ww - 20, sh = 20;
            Draw.roundBox(g, sx, sy, sw, sh, 5, t.c("sidebar.bg"), t.c("panel.border"));
            Icons.draw(g, Icons.Shape.WARN, sx + 8, sy + 6, 8, t.c("state.warn"));
            Draw.label(g, Words.t("rights.changed"), sx + 21, sy + sh / 2f, 12, Weight.SEMIBOLD, t.c("text.primary"));
            String back = Words.t("rights.undo", (left + 999) / 1000);
            int bw = (int) Kit.buttonWidth(back, 12);
            Kit.button(g, t, hits, sx + sw - 6 - bw, sy + 3, bw, 14, back, false, mx, my, () -> undo(h));
        } else {
            undoBits = -1;
        }

        if (presetDrop.open) {
            List<Kit.Option> options = new ArrayList<>();
            int sel = -1;
            for (Rights.Preset p : Rights.Preset.values()) {
                options.add(new Kit.Option(presetName(p, h.solo())));
                if (p == preset) sel = p.ordinal();
            }
            if (preset == null) {
                options.add(new Kit.Option(Words.t("set.custom"), "", false));
                sel = options.size() - 1;
            }
            Kit.dropdownList(g, t, hits, presetDrop, options, sel, 130, mx, my, i -> {
                presetDrop.close();
                if (i < Rights.Preset.values().length) presetEdit(h, Rights.Preset.values()[i]);
            });
        }
    }

    static String presetName(Rights.Preset p, boolean solo) {
        return Words.t((solo ? "set.solo." : "set.") + p.key());
    }

    private static String playerName() {
        LocalPlayer p = Minecraft.getInstance().player;
        return p == null ? "" : p.getGameProfile().getName();
    }

    private void remember(ClaimsNet.Hello h, int now) {
        if (undoBits < 0 || System.currentTimeMillis() > undoUntil) undoBits = now;
        undoUntil = System.currentTimeMillis() + UNDO_MS;
        askedOver = h;
    }

    private void cell(ClaimsNet.Hello h, Rights.Action a, Rights.Column c, boolean on) {
        int now = askedBits >= 0 ? askedBits : h.rights();
        remember(h, now);
        Rights r = Rights.fromBits(now);
        r.toggle(a, c, on);
        askedBits = r.bits();
        LandCache.clearResult();
        LandCache.send(new ClaimsNet.RightsEdit(ClaimsNet.RIGHTS_CELL, a.ordinal(), c.ordinal(), on, 0));
    }

    private void presetEdit(ClaimsNet.Hello h, Rights.Preset p) {
        int now = askedBits >= 0 ? askedBits : h.rights();
        remember(h, now);
        askedBits = Rights.of(p).bits();
        LandCache.clearResult();
        LandCache.send(new ClaimsNet.RightsEdit(ClaimsNet.RIGHTS_PRESET, 0, 0, false, p.ordinal()));
    }

    private void undo(ClaimsNet.Hello h) {
        if (undoBits < 0) return;
        askedBits = undoBits;
        askedOver = h;
        LandCache.send(new ClaimsNet.RightsEdit(ClaimsNet.RIGHTS_TABLE, 0, 0, false, undoBits));
        undoBits = -1;
    }

    // ---- ввод ----

    void toPlayer() {
        LocalPlayer p = Minecraft.getInstance().player;
        if (p == null || !p.level().dimension().equals(dimension)) return;
        centerX = p.getX();
        centerZ = p.getZ();
    }

    private void zoomAt(double sx, double sy, int dir) {
        double bx = blockX(sx), bz = blockZ(sy);
        cell = Math.max(MIN_CELL, Math.min(MAX_CELL, cell * (dir > 0 ? 1.25 : 0.8)));
        centerX = bx - (sx - cx0 - cw / 2.0) / ppb();
        centerZ = bz - (sy - cy0 - ch / 2.0) / ppb();
    }

    private boolean overCanvas(double x, double y) {
        return page == Page.GRID && LandCache.hello() != null && Kit.over(x, y, cx0, cy0, cw, ch) && !overTools((int) x, (int) y);
    }

    @Override
    protected boolean onClick(double x, double y, int button) {
        if (hits.click(x, y, button)) return true;
        if (!overCanvas(x, y)) return super.onClick(x, y, button);
        if (button == 0) {
            selecting = true;
            dragged = false;
            removing = hasControlDown();
            dragFrom = dragTo = chunkAt(x, y);
            return true;
        }
        if (button == 1 || button == 2) {
            panning = true;
            return true;
        }
        return false;
    }

    @Override
    protected boolean onDrag(double x, double y, int button, double dx, double dy) {
        if (selecting) {
            long c = chunkAt(Math.max(cx0, Math.min(cx0 + cw - 1, x)), Math.max(cy0, Math.min(cy0 + ch - 1, y)));
            if (c != dragFrom) dragged = true;
            dragTo = c;
            return true;
        }
        if (panning) {
            centerX -= dx / ppb();
            centerZ -= dy / ppb();
            return true;
        }
        return super.onDrag(x, y, button, dx, dy);
    }

    @Override
    protected boolean onRelease(double x, double y, int button) {
        if (selecting && button == 0) {
            selecting = false;
            commit();
            return true;
        }
        if (panning) {
            panning = false;
            return true;
        }
        return super.onRelease(x, y, button);
    }

    /** Выделение тянутым прямоугольником или кликом; больше 1024 чанков не набирается. */
    private void commit() {
        if (!dragged) {
            if (!SELECTION.remove(dragFrom)) {
                if (SELECTION.size() < ClaimsNet.MAX_EDIT) SELECTION.add(dragFrom);
                else limitNote();
            }
            return;
        }
        int x0 = Math.min(Chunks.x(dragFrom), Chunks.x(dragTo)), x1 = Math.max(Chunks.x(dragFrom), Chunks.x(dragTo));
        int z0 = Math.min(Chunks.z(dragFrom), Chunks.z(dragTo)), z1 = Math.max(Chunks.z(dragFrom), Chunks.z(dragTo));
        dragged = false;
        for (int z = z0; z <= z1; z++) {
            for (int xx = x0; xx <= x1; xx++) {
                long c = Chunks.pack(xx, z);
                if (removing) SELECTION.remove(c);
                else if (SELECTION.size() < ClaimsNet.MAX_EDIT) SELECTION.add(c);
                else {
                    limitNote();
                    return;
                }
            }
        }
    }

    private void limitNote() {
        note = Words.t("panel.limit_note", ClaimsNet.MAX_EDIT);
        noteAt = System.currentTimeMillis();
    }

    @Override
    protected boolean onScroll(double x, double y, double sx, double sy) {
        if (presetDrop.scrolled(x, y, sy)) return true;
        if (overCanvas(x, y) && sy != 0) {
            zoomAt(x, y, sy > 0 ? 1 : -1);
            return true;
        }
        return super.onScroll(x, y, sx, sy);
    }

    @Override
    public boolean keyPressed(int key, int scan, int modifiers) {
        if (presetDrop.keyPressed(key)) return true;
        if (page == Page.GRID) {
            if ((key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) && enterAction != null) {
                enterAction.run();
                return true;
            }
            if (key == GLFW.GLFW_KEY_SPACE) {
                toPlayer();
                return true;
            }
            if (key == GLFW.GLFW_KEY_Z && hasControlDown()) {
                ClaimsNet.Hello h = LandCache.hello();
                if (h != null && h.releasing() > 0) keep();
                return true;
            }
        } else if (key == GLFW.GLFW_KEY_Z && hasControlDown()) {
            ClaimsNet.Hello h = LandCache.hello();
            if (h != null && undoBits >= 0) undo(h);
            return true;
        }
        if (ClaimsClient.OPEN.matches(key, scan)) {
            onClose();
            return true;
        }
        return super.keyPressed(key, scan, modifiers);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /** Для проверок: открытое окно на подразделе «Права». */
    public void rightsForTest() {
        setPage(Page.RIGHTS);
    }
}

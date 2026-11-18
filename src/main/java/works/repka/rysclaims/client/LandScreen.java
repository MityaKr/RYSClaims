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
import works.repka.ryscore.ui.Field;
import works.repka.ryscore.ui.Icons;
import works.repka.ryscore.ui.Kit;
import works.repka.ryscore.ui.ScaledScreen;
import works.repka.ryscore.ui.Type.Weight;
import works.repka.rysclaims.data.Chunks;
import works.repka.rysclaims.data.Rights;
import works.repka.rysclaims.data.TeamLand;
import works.repka.rysclaims.net.ClaimsNet;

/**
 * Окно земли (план 13.1, макеты {@code 35}, {@code 30}–{@code 33}, {@code 36}, {@code 40}–{@code 41b}): подразделы «Сетка»,
 * «Участки», «Права».
 *
 * <p>«Сетка»: полотно чанков вокруг игрока, фон это тайлы карты ({@code MapApi.drawTiles}), если RYSMap есть, иначе тёмное
 * полотно. Своя земля цветом команды, чужая серой штриховкой с именем, освобождаемая красной штриховкой, прогрузка
 * значком. Выделение: тянуть прямоугольник, клик добавляет или убирает чанк, Ctrl убирает; живёт до выхода из мира.
 * Справа панель: команда и роль, два лимита с полосами, три инструмента (занять, прогрузка, освободить), итог выделения,
 * легенда, главная кнопка с Enter. Освобождение идёт с отсчётом, внизу полотна строка «освободятся через … · Оставить».
 *
 * <p>Измерение выбирается списком справа над сеткой. Панель до подтверждения говорит, соединит ли правка участки или
 * разрежет участок (тот же расчёт, что у сервера, на своей копии участков из {@code LandState}).
 *
 * <p>«Участки» (макеты {@code 40}, {@code 40b}, {@code 40c}): две карточки сводки, таблица участков (имя с карандашом, когда и
 * кем занят, измерение, чанки, прогрузка, середина), «На карте» и меню: переименовать, показать на карте, выключить
 * прогрузку, освободить целиком (единственное действие с окном-вопросом).
 *
 * <p>«Права»: четыре действия на три столбца (команда, союзники, все; у одиночки «ты, доверенные, все»), три готовых
 * набора и «Свои», строка «Вернуть» 30 секунд после правки. Править может тот, кому это разрешено в RYSTeams (по
 * умолчанию лидер) и если сервер не закрыл правку. Под таблицей доверенные игроки (права столбца «Союзники») и шесть
 * переключателей команды; закреплённое сервером показано замком.
 *
 * <p>Сервер ничему из окна не верит: окно только показывает и просит, правки проверяются заново.
 */
public final class LandScreen extends ScaledScreen {
    static final int W = 600, H = 364, HEADER = 28, BAR = 26, PANEL = 180, TOOL = 15;
    /** Единиц полотна на чанк: не дальше 10 (план 7.3, «не дальше 12») и не ближе 40. */
    private static final double MIN_CELL = 10, MAX_CELL = 40;
    private static final long RESULT_MS = 6000, UNDO_MS = 30_000;
    private static final int SHIELD_TILE = 0xFFD9714F;

    enum Page { GRID, PLOTS, RIGHTS, LOG }

    enum Tool { CLAIM, FORCE, RELEASE }

    /** Выделение не теряется при закрытии окна, пока игрок в мире (план 7.3). */
    static final LongOpenHashSet SELECTION = new LongOpenHashSet();
    static String selectionDim = "";
    static Tool tool = Tool.CLAIM;
    private static Page lastPage = Page.GRID;

    private final Kit.Hits hits = new Kit.Hits();
    private final Kit.Dropdown presetDrop = new Kit.Dropdown();
    private Page page = lastPage;
    private ResourceKey<Level> dimension;
    private String dim;
    private final Kit.Dropdown dimDrop = new Kit.Dropdown(), visDrop = new Kit.Dropdown();
    /** Участок, чьё меню открыто, и участок, который переименовывается; -1: нет. */
    private int menuPlot = -1, renaming = -1;
    private boolean trusting;
    private final Field rename = new Field(100, 10), trustField = new Field(80, 10);
    private Kit.@Nullable Dialog dialog;
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
        rename.setSpans(List.of(), new int[0]);
        trustField.setSpans(List.of(), new int[0]);
        trustField.setHint(Component.translatable("rysclaims.rights.trusted.hint"));
        rename.visible = renaming >= 0;
        trustField.visible = trusting;
        addWidget(rename);
        addWidget(trustField);
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
        if (page != Page.PLOTS && renaming >= 0) finishRename(false);
        if (page != Page.RIGHTS && trusting) finishTrust(false);
        switch (page) {
            case GRID -> {
                canvas(g, t, slate(), h, mx, my);
                panel(g, t, h, mx, my);
                dimDropdown(g, t, mx, my);
                dimList(g, t, mx, my);
            }
            case PLOTS -> plots(g, t, h, mx, my);
            case RIGHTS -> rights(g, t, h, mx, my);
            case LOG -> journal(g, t, h, mx, my);
        }
        boolean lists = presetDrop.open || dimDrop.open || visDrop.open || menuOpen();
        if (tip != null && !lists && dialog == null) Kit.tooltip(g, t, tip, mx, my);
        if (dialog != null) Kit.dialog(g, t, hits, dialog, mx, my);
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
        ClaimsNet.LandState st = LandCache.state();
        int plotsN = st == null ? 0 : st.plots().size();
        Page[] pages = Page.values();
        int day = lastDay(LandCache.log());
        String[] labels = {Words.t("screen.page.grid"), plotsN > 0 ? Words.t("screen.page.plots.n", plotsN) : Words.t("screen.page.plots"),
            Words.t("screen.page.rights"), day > 0 ? Words.t("screen.page.log.n", day) : Words.t("screen.page.log")};
        float sum = 0;
        for (String l : labels) sum += Draw.labelWidth(l, 12.5f, Weight.MEDIUM);
        int w = (int) Math.ceil(sum) + 12 * labels.length + 2 * (labels.length - 1) + 4;
        if (t.beveled()) Draw.bevel(g, x - 2, y - 2, w, 18, t.c("sidebar.bg"), t.c("panel.bevel.dark"), t.c("panel.bevel.light"), 1, true);
        else Draw.roundBox(g, x - 2, y - 2, w, 18, 5, t.c("sidebar.bg"), t.c("panel.border"));
        for (int i = 0; i < pages.length; i++) {
            Page p = pages[i];
            x += Kit.pill(g, t, hits, x, y, labels[i], page == p, mx, my, () -> setPage(p)) + 2;
        }
    }

    private void setPage(Page p) {
        page = p;
        lastPage = p;
        presetDrop.close();
        dimDrop.close();
        visDrop.close();
        for (Kit.Dropdown d : menus.values()) d.close();
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
        String name = Draw.ellipsize(info.name().isEmpty() ? Words.t("screen.taken") : info.name(), 90, 11.5f, Weight.SEMIBOLD);
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
            String plotsNote = preview(s);
            if (!plotsNote.isEmpty()) {
                for (String line : Draw.wrapLabel(plotsNote, w, 11, Weight.REGULAR, 3)) {
                    Draw.label(g, line, x, y + 3, 11, Weight.REGULAR, t.c("text.secondary"));
                    y += 7;
                }
                y += 4;
            }
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

        // таблица: у одиночки «Ты, Доверенные, Все», у команды столбцы по ролям (макет 41)
        boolean roles = !h.solo();
        int ncol = roles ? Rights.COLUMNS.size() : 3;
        int nameW = roles ? 206 : 250, colW = roles ? 60 : 72, rowH = 19;
        int tableW = nameW + colW * ncol;
        String[][] cols = roles
            ? new String[][] {{Words.t("col.leader"), ""}, {Words.t("col.officer"), ""}, {Words.t("col.member"), ""}, {Words.t("col.guest"), ""},
                {Words.t("col.allies"), Words.t("col.allies.note")}, {Words.t("col.all"), Words.t("col.all.note")}}
            : new String[][] {{Words.t("col.you"), playerName()}, {Words.t("col.trusted"), Words.plural(h.trusted(), "players")}, {Words.t("col.all"), Words.t("col.all.note")}};
        for (int i = 0; i < ncol; i++) {
            float ccx = x + nameW + colW * i + colW / 2f;
            if (roles && i >= 4) Draw.rect(g, (int) (x + nameW + colW * i), top, colW, 20 + rowH * 4, Draw.alpha(t.c("sidebar.bg"), 0.6f));
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
            Draw.label(g, Words.t("right." + a.key()), x + 20, y + 6.5f, 12.5f, Weight.REGULAR, t.c("text.primary"));
            Draw.label(g, Draw.ellipsize(Words.t("right." + a.key() + ".note"), nameW - 24, 11, Weight.REGULAR), x + 20, y + 13.5f, 11, Weight.REGULAR, t.c("text.muted"));
            for (int c = 0; c < ncol; c++) {
                int bx = (int) (x + nameW + colW * c + colW / 2f - 4.5f), by = y + 5;
                if (roles) {
                    String col = Rights.COLUMNS.get(c);
                    boolean on = r.shownRole(a, col);
                    // лидеру на своей земле можно всё: клетка отмечена и не нажимается
                    Runnable toggle = !edit || Rights.LEADER.equals(col) ? null : () -> roleCell(h, a, col, !on);
                    Kit.checkbox(g, t, hits, bx, by, on, mx, my, toggle);
                } else {
                    Rights.Column col = Rights.Column.values()[c];
                    boolean on = r.shown(a, col);
                    // у одиночки первый столбец это он сам (лидер): ему можно всё
                    Runnable toggle = !edit || col == Rights.Column.TEAM ? null : () -> cell(h, a, col, !on);
                    Kit.checkbox(g, t, hits, bx, by, col == Rights.Column.TEAM || on, mx, my, toggle);
                }
            }
            y += rowH;
        }
        Kit.divider(g, t, x, y, tableW);
        y += 6;
        int fullW = ww - 28;
        y = trusted(g, t, h, edit, x, y, fullW, mx, my) + 5;
        Kit.divider(g, t, x, y, fullW);
        y += 5;
        switches(g, t, h, edit, x, y, fullW, mx, my);
        y += 62;

        // пояснения
        List<String> notes = new ArrayList<>();
        notes.add(Words.t(h.solo() ? "rights.note.solo" : "rights.note.leader"));
        if (!edit) notes.add(Words.t(h.rightsLocked() ? "rights.locked" : "rights.no_right"));
        for (String n : notes) {
            for (String line : Draw.wrapLabel(n, fullW, 11.5f, Weight.REGULAR, 2)) {
                Draw.label(g, line, x, y + 4, 11.5f, Weight.REGULAR, t.c("text.muted"));
                y += 8;
            }
            y += 3;
        }
        ClaimsNet.EditResult res = LandCache.result(RESULT_MS);
        if (res != null && (res.op() == ClaimsNet.OP_RIGHTS || res.op() >= ClaimsNet.OP_LAND) && res.done() == 0) {
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
        visList(g, t, mx, my);
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
        LandCache.editedAccess();
        LandCache.send(new ClaimsNet.RightsEdit(ClaimsNet.RIGHTS_CELL, a.ordinal(), c.ordinal(), on, 0));
    }

    private void roleCell(ClaimsNet.Hello h, Rights.Action a, String column, boolean on) {
        int now = askedBits >= 0 ? askedBits : h.rights();
        remember(h, now);
        Rights r = Rights.fromBits(now);
        r.toggleRole(a, column, on);
        askedBits = r.bits();
        LandCache.clearResult();
        LandCache.editedAccess();
        LandCache.send(new ClaimsNet.RightsEdit(ClaimsNet.RIGHTS_ROLE, a.ordinal(), Rights.COLUMNS.indexOf(column), on, 0));
    }

    private void presetEdit(ClaimsNet.Hello h, Rights.Preset p) {
        int now = askedBits >= 0 ? askedBits : h.rights();
        remember(h, now);
        askedBits = Rights.of(p).bits();
        LandCache.clearResult();
        LandCache.editedAccess();
        LandCache.send(new ClaimsNet.RightsEdit(ClaimsNet.RIGHTS_PRESET, 0, 0, false, p.ordinal()));
    }

    private void undo(ClaimsNet.Hello h) {
        if (undoBits < 0) return;
        askedBits = undoBits;
        askedOver = h;
        LandCache.editedAccess();
        LandCache.send(new ClaimsNet.RightsEdit(ClaimsNet.RIGHTS_TABLE, 0, 0, false, undoBits));
        undoBits = -1;
    }

    // ---- измерение ----

    /** Имя измерения: известные по словам окна, остальные по id ({@code mod:some_place} → «Some place»). */
    static String dimName(String id) {
        String key = "rysclaims.dim." + id.replace(':', '.');
        String path = id.substring(id.indexOf(':') + 1).replace('_', ' ');
        String pretty = path.isEmpty() ? id : Character.toUpperCase(path.charAt(0)) + path.substring(1);
        return Component.translatableWithFallback(key, pretty).getString();
    }

    /** Измерения для выбора: те, что знает клиент, и те, где у команды есть участки. Верхний, Нижний, Край первыми. */
    private List<String> dims() {
        java.util.LinkedHashSet<String> set = new java.util.LinkedHashSet<>(List.of("minecraft:overworld", "minecraft:the_nether", "minecraft:the_end"));
        var conn = Minecraft.getInstance().getConnection();
        List<String> rest = new ArrayList<>();
        if (conn != null) for (ResourceKey<Level> k : conn.levels()) rest.add(k.location().toString());
        ClaimsNet.LandState st = LandCache.state();
        if (st != null) for (ClaimsNet.PlotInfo p : st.plots()) rest.add(p.dim());
        java.util.Collections.sort(rest);
        set.addAll(rest);
        if (conn != null) set.removeIf(d -> d.startsWith("minecraft:") && conn.levels().stream().noneMatch(k -> k.location().toString().equals(d)) && !d.equals(dim));
        return new ArrayList<>(set);
    }

    /** Окно на другом измерении: середина на игроке, если он там, иначе на самом большом участке, иначе на нуле. */
    void switchDim(String id) {
        if (id.equals(dim)) return;
        net.minecraft.resources.ResourceLocation loc = net.minecraft.resources.ResourceLocation.tryParse(id);
        if (loc == null) return;
        dimension = ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION, loc);
        dim = id;
        SELECTION.clear();
        selectionDim = dim;
        LocalPlayer p = Minecraft.getInstance().player;
        if (p != null && p.level().dimension().equals(dimension)) {
            centerX = p.getX();
            centerZ = p.getZ();
            return;
        }
        ClaimsNet.PlotInfo best = null;
        ClaimsNet.LandState st = LandCache.state();
        if (st != null) for (ClaimsNet.PlotInfo pl : st.plots()) if (pl.dim().equals(id) && (best == null || pl.chunks().length > best.chunks().length)) best = pl;
        if (best != null && best.chunks().length > 0) {
            long c = works.repka.rysclaims.data.Plots.center(LongArrayList.wrap(best.chunks()));
            centerX = Chunks.x(c) * 16 + 8;
            centerZ = Chunks.z(c) * 16 + 8;
        } else {
            centerX = 0;
            centerZ = 0;
        }
    }

    private void dimDropdown(GuiGraphics g, Theme t, int mx, int my) {
        String label = dimName(dim);
        int w = Kit.dropdownWidth(label, 150);
        Kit.dropdown(g, t, hits, dimDrop, wx + ww - 10 - w, wy + HEADER + 6, 150, label, false, mx, my);
    }

    private void dimList(GuiGraphics g, Theme t, int mx, int my) {
        if (!dimDrop.open) return;
        List<String> ds = dims();
        List<Kit.Option> options = new ArrayList<>();
        int sel = -1;
        ClaimsNet.LandState st = LandCache.state();
        for (int i = 0; i < ds.size(); i++) {
            int n = 0;
            if (st != null) for (ClaimsNet.PlotInfo p : st.plots()) if (p.dim().equals(ds.get(i))) n += p.chunks().length;
            options.add(new Kit.Option(dimName(ds.get(i)), n > 0 ? n : -1));
            if (ds.get(i).equals(dim)) sel = i;
        }
        Kit.dropdownList(g, t, hits, dimDrop, options, sel, 150, mx, my, i -> switchDim(ds.get(i)));
    }

    // ---- подсказка «Соединит участки», «Разделит участок» (макеты 30, 33) ----

    private long previewKey = Long.MIN_VALUE;
    private String previewText = "";

    /** Что станет с участками от главной кнопки: тот же расчёт, что у сервера ({@code Plots.assign}), на своей копии участков. */
    private String preview(Sel s) {
        ClaimsNet.LandState st = LandCache.state();
        if (st == null || s.total == 0 || tool == Tool.FORCE) return "";
        long key = SELECTION.hashCode() * 31L + tool.ordinal() * 1_000_003L + LandCache.stateRevision() * 7919L + dim.hashCode() + LandCache.counter();
        if (key == previewKey) return previewText;
        previewKey = key;
        it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap old = new it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap();
        Map<Integer, String> names = new java.util.LinkedHashMap<>();
        List<String> all = new ArrayList<>();
        int next = 1;
        for (ClaimsNet.PlotInfo p : st.plots()) {
            all.add(p.name());
            next = Math.max(next, p.id() + 1);
            if (!p.dim().equals(dim)) continue;
            names.put(p.id(), p.name());
            for (long c : p.chunks()) old.put(c, p.id());
        }
        LongOpenHashSet chunks = new LongOpenHashSet(old.keySet());
        if (tool == Tool.CLAIM) chunks.addAll(s.freeList);
        else chunks.removeAll(s.release);
        var a = works.repka.rysclaims.data.Plots.assign(chunks, old::get, names, all, next);
        Map<Integer, List<String>> merges = new java.util.LinkedHashMap<>();
        List<String> splits = new ArrayList<>();
        for (var e : a.events) {
            if (e.kind() == works.repka.rysclaims.data.Plots.Event.Kind.MERGE) {
                merges.computeIfAbsent(e.keptId(), k -> new ArrayList<>(List.of(LandCache.plotName(e.keptId(), e.kept())))).add(LandCache.plotName(e.otherId(), e.other()));
            } else {
                splits.add(Words.t("preview.split", LandCache.plotName(e.keptId(), e.kept()), e.other().isEmpty() ? Words.t("plot.new") : e.other()));
            }
        }
        List<String> lines = new ArrayList<>();
        for (List<String> m : merges.values()) lines.add(Words.t("preview.merge", String.join(", ", m.subList(0, m.size() - 1)), m.get(m.size() - 1)));
        lines.addAll(splits);
        previewText = String.join(" ", lines);
        return previewText;
    }

    // ---- участки (макеты 40, 40b, 40c) ----

    private int plotScroll;

    private List<ClaimsNet.PlotInfo> sortedPlots() {
        ClaimsNet.LandState st = LandCache.state();
        if (st == null) return List.of();
        List<ClaimsNet.PlotInfo> list = new ArrayList<>(st.plots());
        list.sort((a, b) -> a.chunks().length != b.chunks().length ? Integer.compare(b.chunks().length, a.chunks().length) : Integer.compare(a.id(), b.id()));
        return list;
    }

    static String when(long at) {
        if (at <= 0) return "";
        java.time.ZoneId zone = java.time.ZoneId.systemDefault();
        java.time.LocalDate day = java.time.Instant.ofEpochMilli(at).atZone(zone).toLocalDate(), today = java.time.LocalDate.now(zone);
        if (day.equals(today)) return Words.t("date.today");
        if (day.equals(today.minusDays(1))) return Words.t("date.yesterday");
        String lang = Minecraft.getInstance().getLanguageManager().getSelected();
        java.util.Locale loc = java.util.Locale.forLanguageTag(lang.replace('_', '-'));
        String pattern = day.getYear() == today.getYear() ? "d MMMM" : "d MMMM yyyy";
        return day.format(java.time.format.DateTimeFormatter.ofPattern(pattern, loc));
    }

    private void plots(GuiGraphics g, Theme t, ClaimsNet.Hello h, int mx, int my) {
        int x = wx + 12, w = ww - 24, top = wy + HEADER + BAR + 4;
        List<ClaimsNet.PlotInfo> list = sortedPlots();
        // сводка: три карточки (макет 40)
        LandCache.wantLog(30_000);
        int cw3 = (w - 12) / 3;
        summary(g, t, x, top, cw3, Icons.Shape.SHIELD, Words.t("plots.card.claims"), h.claimed(), Words.t("plots.of_chunks", h.claimLimit()), h.claimLimit());
        summary(g, t, x + cw3 + 6, top, cw3, Icons.Shape.ANCHOR, Words.t("plots.card.force"), h.forced(), Words.t("plots.of_chunks", h.forceLimit()), h.forceLimit());
        int dx3 = x + 2 * (cw3 + 6), dw3 = w - 2 * (cw3 + 6), day = lastDay(LandCache.log());
        Draw.roundBox(g, dx3, top, dw3, 38, 6, t.c("card.bg"), t.c("card.border"));
        Draw.lock(g, dx3 + 8, top + 6, t.c("text.muted"));
        Draw.label(g, Words.t("plots.card.log"), dx3 + 18, top + 9.5f, 11.5f, Weight.REGULAR, t.c("text.secondary"));
        String dv = Integer.toString(day);
        Draw.label(g, dv, dx3 + 8, top + 21, 17, Weight.SEMIBOLD, t.c("text.primary"));
        Draw.label(g, Words.plural(day, "attempts"), dx3 + 8 + Draw.labelWidth(dv, 17, Weight.SEMIBOLD) + 4, top + 22, 11.5f, Weight.REGULAR, t.c("text.muted"));
        String open = Words.t("plots.card.log.open");
        float ow = Draw.labelWidth(open, 11.5f, Weight.MEDIUM);
        boolean oh = Kit.over(mx, my, dx3 + 8, top + 27, (int) ow, 8);
        Draw.label(g, open, dx3 + 8, top + 31, 11.5f, Weight.MEDIUM, oh ? t.c("text.primary") : t.c("accent"));
        hits.add(dx3 + 8, top + 27, (int) ow, 8, b -> setPage(Page.LOG));
        int y = top + 44;
        if (list.isEmpty()) {
            String text = Words.t(LandCache.state() == null ? "screen.waiting" : "plots.empty");
            float tw = Draw.labelWidth(text, 12.5f, Weight.REGULAR);
            Draw.label(g, text, x + (w - tw) / 2f, y + 40, 12.5f, Weight.REGULAR, t.c("text.muted"));
        } else {
            // шапка таблицы
            int cName = x + 6, cDim = x + 200, cChunks = x + 300, cForce = x + 350, cCenter = x + 400;
            Kit.caps(g, t, Words.t("plots.col.name"), cName, y + 5, 10.5f, 0.44f);
            Kit.caps(g, t, Words.t("plots.col.dim"), cDim, y + 5, 10.5f, 0.44f);
            Kit.caps(g, t, Words.t("plots.col.chunks"), cChunks, y + 5, 10.5f, 0.44f);
            Kit.caps(g, t, Words.t("plots.col.force"), cForce, y + 5, 10.5f, 0.44f);
            Kit.caps(g, t, Words.t("plots.col.center"), cCenter, y + 5, 10.5f, 0.44f);
            y += 11;
            int rowH = 24, bottom = wy + wh - 32;
            int visible = Math.max(1, (bottom - y) / (rowH + 3));
            plotScroll = Math.max(0, Math.min(plotScroll, list.size() - visible));
            for (int i = plotScroll; i < list.size() && i < plotScroll + visible; i++) {
                ClaimsNet.PlotInfo p = list.get(i);
                boolean hot = Kit.over(mx, my, x, y, w, rowH);
                Draw.roundBox(g, x, y, w, rowH, 5, hot || menuPlot == p.id() ? Draw.mix(t.c("card.bg"), t.c("text.primary"), 0.04f) : t.c("card.bg"), t.c("card.border"));
                float cy = y + rowH / 2f;
                String name = LandCache.plotName(p.id(), p.name());
                if (renaming == p.id()) {
                    rename.setX(cName);
                    rename.setY(y + 3);
                    rename.setWidth(180);
                    rename.setHeight(10);
                    rename.visible = true;
                    rename.render(g, mx, my, 0);
                } else {
                    String shown = Draw.ellipsize(name, 170, 12.5f, Weight.SEMIBOLD);
                    Draw.label(g, shown, cName, y + 7.5f, 12.5f, Weight.SEMIBOLD, t.c("text.primary"));
                    if (h.canClaim()) {
                        float px = cName + Draw.labelWidth(shown, 12.5f, Weight.SEMIBOLD) + 4;
                        boolean ph = Kit.over(mx, my, (int) px - 1, y + 3, 9, 9);
                        Icons.draw(g, Icons.Shape.PENCIL, px, y + 4.5f, 6, ph ? t.c("text.primary") : t.c("text.muted"));
                        int id = p.id();
                        hits.add((int) px - 1, y + 3, 9, 9, b -> startRename(id, p.name()));
                    }
                }
                String sub = when(p.at());
                if (!p.by().isEmpty()) sub = sub.isEmpty() ? p.by() : Words.t("plots.claimed_by", sub, p.by());
                else if (!sub.isEmpty()) sub = Words.t("plots.claimed", sub);
                Draw.label(g, Draw.ellipsize(sub, 185, 11, Weight.REGULAR), cName, y + 17, 11, Weight.REGULAR, t.c("text.muted"));
                Draw.label(g, Draw.ellipsize(dimName(p.dim()), 95, 12, Weight.REGULAR), cDim, cy, 12, Weight.REGULAR, t.c("text.secondary"));
                Draw.label(g, Integer.toString(p.chunks().length), cChunks, cy, 12, Weight.SEMIBOLD, t.c("text.primary"));
                if (p.forced() > 0) {
                    Icons.draw(g, Icons.Shape.ANCHOR, cForce, cy - 3, 6, t.c("text.secondary"));
                    Draw.label(g, Integer.toString(p.forced()), cForce + 9, cy, 12, Weight.REGULAR, t.c("text.secondary"));
                } else {
                    Draw.label(g, Words.t("plots.no"), cForce, cy, 12, Weight.REGULAR, t.c("text.dim"));
                }
                if (p.chunks().length > 0) {
                    long c = works.repka.rysclaims.data.Plots.center(LongArrayList.wrap(p.chunks()));
                    Draw.label(g, Words.t("plots.center", Chunks.x(c) * 16 + 8, Chunks.z(c) * 16 + 8), cCenter, cy, 12, Weight.REGULAR, t.c("text.secondary"));
                }
                // «На карте» и меню
                String map = Words.t("plots.on_map");
                int bw = (int) Kit.buttonWidth(map, 12);
                int menuW = Kit.dropdownWidth("···", 40);
                int bx = x + w - 6 - menuW - 4 - bw;
                ClaimsNet.PlotInfo plot = p;
                Kit.button(g, t, hits, bx, y + 5, bw, 14, map, false, mx, my, () -> showOnMap(plot));
                Kit.Dropdown md = menus.computeIfAbsent(p.id(), k -> new Kit.Dropdown());
                Kit.dropdown(g, t, hits, md, x + w - 6 - menuW, y + 5, 40, "···", false, mx, my);
                if (md.open && menuPlot != p.id()) {
                    Kit.Dropdown prev = menus.get(menuPlot);
                    if (prev != null) prev.close();
                    menuPlot = p.id();
                }
                y += rowH + 3;
            }
            if (list.size() > visible) {
                String more = Words.t("plots.scroll", plotScroll + 1, Math.min(list.size(), plotScroll + visible), list.size());
                Draw.label(g, more, x, wy + wh - 22, 11, Weight.REGULAR, t.c("text.muted"));
            }
        }
        // итог последней правки участка
        ClaimsNet.EditResult res = LandCache.result(RESULT_MS);
        if (res != null && res.op() >= ClaimsNet.OP_LAND) {
            String text = Words.result(res);
            Draw.label(g, Draw.ellipsize(text, w - 160, 11.5f, Weight.REGULAR), x, wy + wh - 13, 11.5f, Weight.REGULAR, res.done() == 0 ? deny(t) : t.c("text.muted"));
        }
        // «Занять ещё на карте»
        String more = Words.t("plots.claim_more");
        int mw = (int) Kit.buttonWidth(more, 13) + 14;
        Kit.button(g, t, hits, wx + ww - 12 - mw, wy + wh - 24, mw, 17, more, true, mx, my, () -> setPage(Page.GRID));
        Kit.Dropdown plotMenu = menus.get(menuPlot);
        if (plotMenu != null && plotMenu.open) {
            ClaimsNet.PlotInfo p = LandCache.plot(menuPlot);
            if (p == null) {
                plotMenu.close();
            } else {
                List<Kit.Option> options = List.of(
                    new Kit.Option(Words.t("plots.menu.rename"), "", h.canClaim()),
                    new Kit.Option(Words.t("plots.menu.show"), "", true),
                    new Kit.Option(Words.t("plots.menu.unload"), "", h.canForce() && p.forced() > 0),
                    new Kit.Option(Words.t("plots.menu.release"), "", h.canClaim()));
                Kit.dropdownList(g, t, hits, plotMenu, options, -1, 150, mx, my, i -> {
                    switch (i) {
                        case 0 -> startRename(p.id(), p.name());
                        case 1 -> showOnMap(p);
                        case 2 -> landEdit(ClaimsNet.LAND_UNLOAD_PLOT, p.id(), false, "");
                        default -> askRelease(p);
                    }
                });
            }
        } else {
            menuPlot = -1;
        }
    }

    /** Меню «···» у каждой строки участков: своё на участок, открыто самое большее одно. */
    private final Map<Integer, Kit.Dropdown> menus = new HashMap<>();

    private boolean menuOpen() {
        for (Kit.Dropdown d : menus.values()) if (d.open) return true;
        return false;
    }

    private void summary(GuiGraphics g, Theme t, int x, int y, int w, Icons.Shape icon, String title, int value, String of, int limit) {
        Draw.roundBox(g, x, y, w, 38, 6, t.c("card.bg"), t.c("card.border"));
        Icons.draw(g, icon, x + 8, y + 6, 6.5f, t.c("text.muted"));
        Draw.label(g, title, x + 18, y + 9.5f, 11.5f, Weight.REGULAR, t.c("text.secondary"));
        String v = Integer.toString(value);
        Draw.label(g, v, x + 8, y + 21, 17, Weight.SEMIBOLD, t.c("text.primary"));
        Draw.label(g, of, x + 8 + Draw.labelWidth(v, 17, Weight.SEMIBOLD) + 4, y + 22, 11.5f, Weight.REGULAR, t.c("text.muted"));
        int by = y + 30, bw = w - 16;
        Draw.round(g, x + 8, by, bw, 3, 1.5f, t.c("slot.bg"));
        if (limit > 0) Draw.round(g, x + 8, by, Math.min(bw, bw * value / (float) limit), 3, 1.5f, value > limit ? deny(t) : t.c("text.secondary"));
    }

    private void startRename(int id, String current) {
        renaming = id;
        rename.setValue(current);
        rename.setMaxLength(works.repka.rysclaims.data.Plots.MAX_NAME);
        rename.visible = true;
        rename.setFocused(true);
        rename.moveCursorToEnd(false);
        rename.setHighlightPos(0);
        setFocused(rename);
    }

    private void finishRename(boolean save) {
        if (save && renaming >= 0) landEdit(ClaimsNet.LAND_RENAME, renaming, false, rename.getValue().trim());
        renaming = -1;
        rename.visible = false;
        rename.setFocused(false);
        setFocused(null);
    }

    /** «На карте»: окно карты RYSMap на середине участка; без RYSMap сетка этого окна. */
    private void showOnMap(ClaimsNet.PlotInfo p) {
        if (p.chunks().length == 0) return;
        long c = works.repka.rysclaims.data.Plots.center(LongArrayList.wrap(p.chunks()));
        showOnMap(p.dim(), Chunks.x(c) * 16 + 8, Chunks.z(c) * 16 + 8, LandCache.plotName(p.id(), p.name()));
    }

    /** Точка на карте RYSMap; без RYSMap сетка этого окна на ней. */
    private void showOnMap(String dimId, int x, int z, String label) {
        net.minecraft.resources.ResourceLocation loc = net.minecraft.resources.ResourceLocation.tryParse(dimId);
        if (loc == null) return;
        if (MapApi.get().present()) {
            ResourceKey<Level> key = ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION, loc);
            MapApi.get().open(MapApi.Target.builder(key, x, 64, z).label(Component.literal(label)).build());
            return;
        }
        switchDim(dimId);
        centerX = x;
        centerZ = z;
        setPage(Page.GRID);
    }

    /** Освобождение участка целиком: единственное действие с окном-вопросом (макет 40c). */
    private void askRelease(ClaimsNet.PlotInfo p) {
        String name = LandCache.plotName(p.id(), p.name());
        ClaimsNet.Hello h = LandCache.hello();
        int seconds = h == null ? 30 : h.releaseSeconds();
        Kit.Dialog d = new Kit.Dialog(Words.t("plots.release.title", name))
            .text(Words.t("plots.release.text", Words.chunks(p.chunks().length), seconds));
        if (p.forced() > 0) d.text(Words.t("plots.release.forced", p.forced()));
        dialog = d.no(Words.t("plots.release.no"), () -> dialog = null)
            .yes(Words.t("plots.release.yes"), () -> {
                dialog = null;
                landEdit(ClaimsNet.LAND_RELEASE_PLOT, p.id(), false, "");
            });
    }

    private void landEdit(int kind, int id, boolean on, String text) {
        LandCache.clearResult();
        if (kind == ClaimsNet.LAND_TRUST || kind == ClaimsNet.LAND_UNTRUST || kind == ClaimsNet.LAND_FLAG) LandCache.editedAccess();
        LandCache.send(new ClaimsNet.LandEdit(kind, id, on, text));
    }

    // ---- права: доверенные и переключатели (макет 41) ----

    private static final TeamLand.Flag[] SWITCHES = {TeamLand.Flag.OWN_EXPLOSIONS, TeamLand.Flag.MOB_GRIEF, TeamLand.Flag.PVP,
        TeamLand.Flag.MACHINES_INSIDE, TeamLand.Flag.MACHINES_OUTSIDE};

    /** Строка доверенных игроков: ник с крестиком, «+ Добавить» открывает поле ника. Возвращает низ строки. */
    private int trusted(GuiGraphics g, Theme t, ClaimsNet.Hello h, boolean edit, int x, int y, int w, int mx, int my) {
        ClaimsNet.LandState st = LandCache.state();
        String label = Words.t("rights.trusted.label");
        Draw.label(g, label, x, y + 7, 12, Weight.REGULAR, t.c("text.secondary"));
        float cx = x + Draw.labelWidth(label, 12, Weight.REGULAR) + 8;
        List<ClaimsNet.Trusted> list = st == null ? List.of() : st.trusted();
        for (ClaimsNet.Trusted tr : list) {
            float nw = Draw.labelWidth(tr.name(), 12, Weight.REGULAR);
            int chipW = (int) Math.ceil(5 + nw + (edit ? 4 + 6 : 0) + 5);
            if (cx + chipW > x + w - 70) break;
            Draw.roundBox(g, cx, y + 1, chipW, 13, 4, t.c("sidebar.bg"), t.c("panel.border"));
            Draw.label(g, tr.name(), cx + 5, y + 7.5f, 12, Weight.REGULAR, t.c("text.primary"));
            if (edit) {
                float kx = cx + 5 + nw + 4;
                boolean hot = Kit.over(mx, my, (int) kx - 1, y + 3, 8, 9);
                Icons.draw(g, Icons.Shape.CROSS, kx, y + 4.5f, 5, hot ? deny(t) : t.c("text.muted"));
                UUID id = tr.id();
                hits.add((int) kx - 1, y + 3, 8, 9, b -> landEdit(ClaimsNet.LAND_UNTRUST, 0, false, id.toString()));
            }
            cx += chipW + 4;
        }
        if (list.isEmpty() && !trusting) {
            Draw.label(g, Words.t("rights.trusted.none"), cx, y + 7.5f, 11.5f, Weight.REGULAR, t.c("text.muted"));
            cx += Draw.labelWidth(Words.t("rights.trusted.none"), 11.5f, Weight.REGULAR) + 8;
        }
        if (edit) {
            if (trusting) {
                trustField.setX((int) cx);
                trustField.setY(y + 2);
                trustField.setWidth(90);
                trustField.setHeight(10);
                trustField.visible = true;
                trustField.render(g, mx, my, 0);
                String ok = Words.t("rights.trusted.ok");
                int ow = (int) Kit.buttonWidth(ok, 12);
                Kit.button(g, t, hits, (int) cx + 94, y + 1, ow, 13, ok, true, mx, my, () -> finishTrust(true));
            } else {
                String add = Words.t("rights.trusted.add");
                int aw = (int) Kit.buttonWidth(add, 12);
                Kit.button(g, t, hits, (int) cx, y + 1, aw, 13, add, false, mx, my, this::startTrust);
            }
        }
        return y + 16;
    }

    private void startTrust() {
        trusting = true;
        trustField.setValue("");
        trustField.setMaxLength(36);
        trustField.visible = true;
        trustField.setFocused(true);
        setFocused(trustField);
    }

    private void finishTrust(boolean save) {
        String name = trustField.getValue().trim();
        if (save && !name.isEmpty()) landEdit(ClaimsNet.LAND_TRUST, 0, true, name);
        trusting = false;
        trustField.visible = false;
        trustField.setFocused(false);
        setFocused(null);
    }

    /** Шесть переключателей в два столбца; закреплённое сервером с замком и словами «решает сервер». */
    private void switches(GuiGraphics g, Theme t, ClaimsNet.Hello h, boolean edit, int x, int y, int w, int mx, int my) {
        ClaimsNet.LandState st = LandCache.state();
        int colW = (w - 16) / 2, rowH = 20;
        for (int i = 0; i < 6; i++) {
            int cx = x + (i < 3 ? 0 : colW + 16), cy = y + (i % 3) * rowH;
            if (i == 5) {
                visibility(g, t, st, edit, cx, cy, colW, mx, my);
                continue;
            }
            TeamLand.Flag f = SWITCHES[i];
            String key = "switch." + f.key;
            Draw.label(g, Words.t(key), cx, cy + 5, 12, Weight.REGULAR, t.c("text.primary"));
            Draw.label(g, Draw.ellipsize(Words.t(key + ".note"), colW - 30, 10.5f, Weight.REGULAR), cx, cy + 12.5f, 10.5f, Weight.REGULAR, t.c("text.muted"));
            boolean on = st == null ? f.def : (st.flags() >> f.ordinal() & 1) != 0;
            String lock = null;
            if (st != null && (st.lockMask() >> f.ordinal() & 1) != 0) {
                on = (st.lockValues() >> f.ordinal() & 1) != 0;
                lock = Words.t("switch.locked", Words.t(on ? "switch.on" : "switch.off"));
            } else if (f == TeamLand.Flag.PVP && st != null && st.pvpMode() != works.repka.rysclaims.data.Settings.PvpMode.TEAM.ordinal()) {
                on = st.pvpMode() == works.repka.rysclaims.data.Settings.PvpMode.ALWAYS.ordinal();
                lock = Words.t("switch.locked", Words.t(on ? "switch.pvp.always" : "switch.pvp.never"));
            }
            int tx = cx + colW - 18;
            if (lock != null) {
                float lw = Draw.labelWidth(lock, 10.5f, Weight.REGULAR);
                Draw.label(g, lock, tx - 5 - lw, cy + 8, 10.5f, Weight.REGULAR, t.c("text.muted"));
                Draw.lock(g, (int) (tx - 5 - lw - 8), cy + 4, t.c("text.muted"));
            }
            boolean can = edit && lock == null;
            boolean hot = can && Kit.over(mx, my, tx, cy + 3, 18, 10);
            Kit.toggle(g, t, tx, cy + 3, on, hot);
            if (can) {
                boolean next = !on;
                hits.add(tx, cy + 3, 18, 10, b -> landEdit(ClaimsNet.LAND_FLAG, f.ordinal(), next, ""));
            }
        }
    }

    private void visibility(GuiGraphics g, Theme t, ClaimsNet.@Nullable LandState st, boolean edit, int cx, int cy, int colW, int mx, int my) {
        Draw.label(g, Words.t("switch.visibility"), cx, cy + 5, 12, Weight.REGULAR, t.c("text.primary"));
        Draw.label(g, Draw.ellipsize(Words.t("switch.visibility.note"), colW - 80, 10.5f, Weight.REGULAR), cx, cy + 12.5f, 10.5f, Weight.REGULAR, t.c("text.muted"));
        int v = st == null ? 0 : st.visibility();
        String label = Words.t("visibility." + TeamLand.Visibility.values()[Math.max(0, Math.min(2, v))].key());
        int dw = Kit.dropdownWidth(label, 80);
        if (edit) Kit.dropdown(g, t, hits, visDrop, cx + colW - dw, cy + 2, 80, label, v != 0, mx, my);
        else Draw.label(g, label, cx + colW - Draw.labelWidth(label, 12, Weight.REGULAR), cy + 8, 12, Weight.REGULAR, t.c("text.secondary"));
    }

    private void visList(GuiGraphics g, Theme t, int mx, int my) {
        if (!visDrop.open) return;
        ClaimsNet.LandState st = LandCache.state();
        List<Kit.Option> options = new ArrayList<>();
        for (TeamLand.Visibility v : TeamLand.Visibility.values()) options.add(new Kit.Option(Words.t("visibility." + v.key())));
        Kit.dropdownList(g, t, hits, visDrop, options, st == null ? 0 : st.visibility(), 100, mx, my, i -> landEdit(ClaimsNet.LAND_VISIBILITY, i, false, ""));
    }

    // ---- журнал (макет 43) ----

    /** Фильтр журнала: все, игроки, машины, взрывы. */
    private int logFilter;
    private int logScroll;

    private static int kind(ClaimsNet.LogLine l) {
        if ("explosion".equals(l.reason())) return 3;
        return l.machine() ? 2 : 1;
    }

    /** Сколько строк журнала за последние сутки (карточка «Защита за сутки», счёт у пилюли). */
    static int lastDay(ClaimsNet.@Nullable LogPage p) {
        if (p == null) return 0;
        long border = System.currentTimeMillis() - 86_400_000L;
        int n = 0;
        for (ClaimsNet.LogLine l : p.lines()) if (l.last() >= border) n++;
        return n;
    }

    /** Кто: ник, машина или тип взрыва (id сущности переводится по названию сущности). */
    static String who(ClaimsNet.LogLine l) {
        if (l.name().isEmpty()) return Words.t("log.nobody");
        if (l.name().indexOf(':') > 0 && !l.machine()) {
            var type = net.minecraft.world.entity.EntityType.byString(l.name());
            if (type.isPresent()) return type.get().getDescription().getString();
        }
        return l.name();
    }

    static String whenShort(long at) {
        java.time.ZoneId zone = java.time.ZoneId.systemDefault();
        var t = java.time.Instant.ofEpochMilli(at).atZone(zone);
        if (t.toLocalDate().equals(java.time.LocalDate.now(zone))) return t.format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"));
        return when(at);
    }

    private void journal(GuiGraphics g, Theme t, ClaimsNet.Hello h, int mx, int my) {
        LandCache.wantLog(5000);
        ClaimsNet.LogPage page = LandCache.log();
        int x = wx + 12, top = wy + HEADER + BAR + 4, sideW = 170, w = ww - 24 - sideW - 12;
        List<ClaimsNet.LogLine> all = page == null ? List.of() : page.lines();
        int[] counts = new int[4];
        counts[0] = all.size();
        for (ClaimsNet.LogLine l : all) counts[kind(l)]++;
        // фильтры-чипы
        int cx = x;
        String[] names = {Words.t("log.filter.all"), Words.t("log.filter.players"), Words.t("log.filter.machines"), Words.t("log.filter.explosions")};
        for (int i = 0; i < 4; i++) {
            int f = i;
            cx += Kit.pill(g, t, hits, cx, top, names[i] + " · " + counts[i], logFilter == i, mx, my, () -> {
                logFilter = f;
                logScroll = 0;
            }) + 3;
        }
        int y = top + 20;
        List<ClaimsNet.LogLine> list = new ArrayList<>();
        for (ClaimsNet.LogLine l : all) if (logFilter == 0 || kind(l) == logFilter) list.add(l);
        if (page == null || list.isEmpty()) {
            String text = Words.t(page == null ? "screen.waiting" : "log.empty");
            float tw = Draw.labelWidth(text, 12.5f, Weight.REGULAR);
            Draw.label(g, text, x + (w - tw) / 2f, y + 40, 12.5f, Weight.REGULAR, t.c("text.muted"));
        } else {
            int rowH = 18, bottom = wy + wh - 14;
            int visible = Math.max(1, (bottom - y) / rowH);
            logScroll = Math.max(0, Math.min(logScroll, list.size() - visible));
            int shown = Math.min(visible, list.size() - logScroll);
            Draw.roundBox(g, x, y, w, shown * rowH, 5, t.c("card.bg"), t.c("card.border"));
            for (int i = 0; i < shown; i++) {
                ClaimsNet.LogLine l = list.get(logScroll + i);
                int ry = y + i * rowH;
                float cy = ry + rowH / 2f;
                if (i > 0) Draw.rect(g, x + 6, ry, w - 12, 1, t.c("card.border"));
                Draw.label(g, whenShort(l.last()), x + 6, cy, 11.5f, Weight.REGULAR, t.c("text.muted"));
                Icons.Shape icon = switch (l.reason()) {
                    case "build", "guest", "wilderness" -> Icons.Shape.PICK;
                    case "open" -> Icons.Shape.ARCHIVE;
                    case "use" -> Icons.Shape.KEY;
                    case "entity" -> Icons.Shape.HAND;
                    case "explosion" -> Icons.Shape.SPARK;
                    default -> l.machine() ? Icons.Shape.GEAR : Icons.Shape.WARN;
                };
                Draw.roundBox(g, x + 40, ry + 3, 12, 12, 3, t.c("slot.bg"), t.c("card.border"));
                Icons.draw(g, icon, x + 42.5f, ry + 5.5f, 7, t.c("text.secondary"));
                String what = Words.t((l.machine() ? "log.what.machine." : "log.what.") + l.reason());
                String whoName = who(l);
                String count = l.count() > 1 ? " ×" + l.count() : "";
                String where = (l.plot().isEmpty() ? "" : (l.plot().startsWith("#") ? Words.t("plot.unnamed", l.plot().substring(1)) : l.plot()) + " · ")
                    + Words.t("log.coords", l.x(), l.y(), l.z());
                String map = Words.t("plots.on_map");
                int bw = (int) Kit.buttonWidth(map, 11.5f);
                float whereW = Draw.labelWidth(where, 11, Weight.REGULAR);
                float textRoom = w - 58 - bw - 10 - whereW - 10;
                String text = Draw.ellipsize(what + " · " + whoName + count, textRoom, 12, Weight.REGULAR);
                Draw.label(g, text, x + 58, cy, 12, Weight.REGULAR, t.c("text.primary"));
                Draw.label(g, where, x + w - 6 - bw - 6 - whereW, cy, 11, Weight.REGULAR, t.c("text.muted"));
                if (Kit.over(mx, my, x + 58, ry, (int) textRoom, rowH)) {
                    List<String> tipLines = new ArrayList<>(List.of(what + " · " + whoName + count));
                    if (l.count() > 1 && l.first() < l.last()) tipLines.add(Words.t("log.span", whenShort(l.first()), whenShort(l.last())));
                    tip = tipLines;
                }
                ClaimsNet.LogLine line = l;
                Kit.button(g, t, hits, x + w - 6 - bw, ry + 3, bw, 12, map, false, mx, my, () -> showOnMap(line.dim(), line.x(), line.z(), ""));
            }
            if (list.size() > visible) {
                Draw.label(g, Words.t("plots.scroll", logScroll + 1, logScroll + shown, list.size()), x, wy + wh - 8, 11, Weight.REGULAR, t.c("text.muted"));
            }
        }
        // настройки справа
        int sx = x + w + 12, sy = top + 2;
        Kit.caps(g, t, Words.t("log.settings"), sx, sy + 3, 10.5f, 0.44f);
        sy += 12;
        boolean edit = page != null && page.canEdit();
        sy = logToggle(g, t, sx, sy, sideW, Words.t("log.feed"), Words.t("log.feed.note"), page != null && page.feed(), edit, ClaimsNet.LAND_LOG_FEED, mx, my);
        sy = logToggle(g, t, sx, sy, sideW, Words.t("log.toast"), Words.t("log.toast.note"), page != null && page.toast(), edit, ClaimsNet.LAND_LOG_TOAST, mx, my);
        callout(g, t, sx, sy + 2, sideW, Words.t("log.info", page == null ? 7 : page.days()));
    }

    private int logToggle(GuiGraphics g, Theme t, int x, int y, int w, String title, String note, boolean on, boolean edit, int kind, int mx, int my) {
        Draw.label(g, title, x, y + 5, 12, Weight.SEMIBOLD, t.c("text.primary"));
        List<String> lines = Draw.wrapLabel(note, w - 26, 10.5f, Weight.REGULAR, 2);
        float ly = y + 12.5f;
        for (String line : lines) {
            Draw.label(g, line, x, ly, 10.5f, Weight.REGULAR, t.c("text.muted"));
            ly += 7;
        }
        int tx = x + w - 18;
        boolean hot = edit && Kit.over(mx, my, tx, y + 2, 18, 10);
        Kit.toggle(g, t, tx, y + 2, on, hot);
        if (edit) {
            boolean next = !on;
            hits.add(tx, y + 2, 18, 10, b -> landEdit(kind, 0, next, ""));
        }
        return (int) ly + 6;
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
        if (dialog != null) {
            hits.click(x, y, button);
            return true;
        }
        if (renaming >= 0 && rename.isMouseOver(x, y)) {
            rename.mouseClicked(x, y, button);
            return true;
        }
        if (trusting && trustField.isMouseOver(x, y)) {
            trustField.mouseClicked(x, y, button);
            return true;
        }
        if (hits.click(x, y, button)) return true;
        if (renaming >= 0) finishRename(true);
        if (trusting) finishTrust(false);
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
        if (presetDrop.scrolled(x, y, sy) || dimDrop.scrolled(x, y, sy) || visDrop.scrolled(x, y, sy)) return true;
        if (page == Page.PLOTS && sy != 0) {
            plotScroll += sy > 0 ? -1 : 1;
            return true;
        }
        if (page == Page.LOG && sy != 0) {
            logScroll += sy > 0 ? -2 : 2;
            return true;
        }
        if (overCanvas(x, y) && sy != 0) {
            zoomAt(x, y, sy > 0 ? 1 : -1);
            return true;
        }
        return super.onScroll(x, y, sx, sy);
    }

    @Override
    public boolean keyPressed(int key, int scan, int modifiers) {
        if (dialog != null) return dialog.keyPressed(key);
        if (renaming >= 0 || trusting) {
            boolean enter = key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER;
            if (enter || key == GLFW.GLFW_KEY_ESCAPE) {
                if (renaming >= 0) finishRename(enter);
                else finishTrust(enter);
                return true;
            }
            return super.keyPressed(key, scan, modifiers);
        }
        if (presetDrop.keyPressed(key) || dimDrop.keyPressed(key) || visDrop.keyPressed(key)) return true;
        for (Kit.Dropdown d : menus.values()) if (d.keyPressed(key)) return true;
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

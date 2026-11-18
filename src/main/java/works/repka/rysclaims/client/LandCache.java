package works.repka.rysclaims.client;

import it.unimi.dsi.fastutil.longs.Long2LongLinkedOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;
import works.repka.ryscore.ui.Icons;
import works.repka.ryscore.ui.Notice;
import works.repka.ryscore.ui.Notices;
import works.repka.rysclaims.data.Chunks;
import works.repka.rysclaims.net.ClaimsNet;
import works.repka.rysclaims.net.RegionBytes;

/**
 * Земля на клиенте: регионы подписки, команды из палитр, своя команда ({@code Hello}), итог последней правки.
 *
 * <p>Подписка собирается сама: окно земли и слой карты зовут {@link #want} для регионов, которые рисуют, а раз в полсекунды
 * кэш собирает список (3×3 региона вокруг игрока, затем нужные окну, ближние первыми, не больше 64) и шлёт его, если он
 * изменился, не чаще раза в секунду. Регион, выпавший из подписки, забывается: сервер больше не шлёт его правки.
 * Только главный поток клиента.
 */
public final class LandCache {
    /** Команда, как её знает клиент: имя, цвет, что можно игроку на её земле (биты {@code ClaimsNet.BUILD} и дальше). */
    public record TeamInfo(UUID id, String name, int color, int access) {
        public boolean can(int bit) {
            return (access & bit) != 0;
        }
    }

    /** Чанк, как его знает клиент. */
    public record ChunkState(TeamInfo team, boolean force, boolean releasing) {}

    private static final class Region {
        final UUID[] palette = new UUID[RegionBytes.MAX_PALETTE];
        int size;
        byte[] codes = new byte[RegionBytes.SIZE];
        long revision;

        int indexOf(UUID team) {
            for (int i = 0; i < size; i++) if (palette[i].equals(team)) return i;
            if (size < palette.length) {
                palette[size] = team;
                return size++;
            }
            return palette.length - 1;
        }
    }

    /** Сколько живёт просьба окна о регионе. */
    private static final long WANT_MS = 5000;
    private static final Map<UUID, TeamInfo> TEAMS = new HashMap<>();
    private static final Long2ObjectOpenHashMap<Region> REGIONS = new Long2ObjectOpenHashMap<>();
    private static LongOpenHashSet subscribed = new LongOpenHashSet();
    private static String dimension = "";
    private static final Long2LongLinkedOpenHashMap WANT = new Long2LongLinkedOpenHashMap();
    private static String wantDim = "";
    private static long counter, lastSubscribe;
    private static int ticks;
    private static ClaimsNet.@Nullable Hello hello;
    private static long helloAt;
    private static ClaimsNet.@Nullable EditResult result;
    private static long resultAt;

    private LandCache() {}

    // ---- пакеты ----

    public static void hello(ClaimsNet.Hello h) {
        hello = h;
        helloAt = System.currentTimeMillis();
    }

    public static void region(ClaimsNet.Region r) {
        long key = Chunks.pack(r.rx(), r.rz());
        if (!r.dimension().equals(dimension) || !subscribed.contains(key)) return;
        Region reg = new Region();
        try {
            reg.codes = RegionBytes.decode(r.data());
        } catch (IllegalArgumentException e) {
            return;
        }
        for (ClaimsNet.TeamEntry e : r.palette()) {
            learn(e);
            if (reg.size < reg.palette.length) reg.palette[reg.size++] = e.id();
        }
        reg.revision = ++counter;
        REGIONS.put(key, reg);
    }

    public static void diff(ClaimsNet.Diff d) {
        if (!d.dimension().equals(dimension)) return;
        for (ClaimsNet.TeamEntry e : d.palette()) learn(e);
        for (int i = 0; i < d.chunks().length; i++) {
            long chunk = d.chunks()[i];
            Region reg = REGIONS.get(RegionBytes.regionOf(chunk));
            if (reg == null) continue;
            int code = d.codes()[i] & 0xFF;
            int team = code & RegionBytes.TEAM_MASK;
            int at = RegionBytes.index(Chunks.x(chunk), Chunks.z(chunk));
            if (team == 0 || team > d.palette().size()) reg.codes[at] = 0;
            else reg.codes[at] = RegionBytes.code(reg.indexOf(d.palette().get(team - 1).id()), (code & RegionBytes.FORCE) != 0, (code & RegionBytes.RELEASING) != 0);
            reg.revision = ++counter;
        }
    }

    public static void access(ClaimsNet.Access a) {
        for (int i = 0; i < a.teams().size() && i < a.bits().length; i++) {
            TeamInfo old = TEAMS.get(a.teams().get(i));
            if (old != null) TEAMS.put(old.id(), new TeamInfo(old.id(), old.name(), old.color(), a.bits()[i]));
        }
        counter++;
    }

    public static void result(ClaimsNet.EditResult r) {
        result = r;
        resultAt = System.currentTimeMillis();
        // правка клавишей «занять чанк под ногами»: окна нет, итог приходит строкой в колонке тостов
        if (!(Minecraft.getInstance().screen instanceof LandScreen) && r.op() == ClaimsNet.OP_CLAIM) {
            Notice n = Notice.row(Notice.CLAIMS, "claim_here").icon(Icons.Shape.SHIELD).title(Words.result(r));
            if (r.done() == 0) n.bad();
            Notices.show(n);
        }
    }

    private static void learn(ClaimsNet.TeamEntry e) {
        TEAMS.put(e.id(), new TeamInfo(e.id(), e.name(), e.color(), e.access()));
    }

    // ---- вопросы ----

    /** Свежая своя команда или null, если сервер её не прислал (на сервере нет RYSClaims). */
    public static ClaimsNet.@Nullable Hello hello() {
        return hello;
    }

    /** Сколько миллисекунд до первого освобождения своей команды. */
    public static long releaseLeft() {
        ClaimsNet.Hello h = hello;
        if (h == null || h.releasing() == 0) return 0;
        return Math.max(0, h.releaseIn() - (System.currentTimeMillis() - helloAt));
    }

    public static ClaimsNet.@Nullable EditResult result(long maxAgeMs) {
        return result != null && System.currentTimeMillis() - resultAt <= maxAgeMs ? result : null;
    }

    public static void clearResult() {
        result = null;
    }

    /** Знает ли сервер про окно земли: канал есть. */
    public static boolean available() {
        ClientPacketListener c = Minecraft.getInstance().getConnection();
        return c != null && c.hasChannel(ClaimsNet.Edit.TYPE);
    }

    /** Пришёл ли регион чанка. */
    public static boolean known(String dim, int cx, int cz) {
        return dim.equals(dimension) && REGIONS.containsKey(Chunks.pack(cx >> 5, cz >> 5));
    }

    /** Чанк или null: ничья земля или регион ещё не пришёл. */
    public static @Nullable ChunkState at(String dim, int cx, int cz) {
        if (!dim.equals(dimension)) return null;
        Region reg = REGIONS.get(Chunks.pack(cx >> 5, cz >> 5));
        if (reg == null) return null;
        int code = reg.codes[RegionBytes.index(cx, cz)] & 0xFF;
        int team = code & RegionBytes.TEAM_MASK;
        if (team == 0 || team > reg.size) return null;
        TeamInfo info = TEAMS.get(reg.palette[team - 1]);
        if (info == null) return null;
        return new ChunkState(info, (code & RegionBytes.FORCE) != 0, (code & RegionBytes.RELEASING) != 0);
    }

    public static @Nullable TeamInfo team(UUID id) {
        return TEAMS.get(id);
    }

    /** Номер версии региона: растёт при любой правке, при пропаже региона и при смене прав. Заодно просьба о регионе. */
    public static long revision(String dim, int rx, int rz) {
        want(dim, rx, rz);
        Region reg = dim.equals(dimension) ? REGIONS.get(Chunks.pack(rx, rz)) : null;
        return reg == null ? -counter : reg.revision;
    }

    /** Общий номер: растёт при любой перемене земли на клиенте. */
    public static long counter() {
        return counter;
    }

    /** Окно или карта рисует этот регион: он нужен в подписке. */
    public static void want(String dim, int rx, int rz) {
        if (!dim.equals(wantDim)) {
            WANT.clear();
            wantDim = dim;
        }
        WANT.putAndMoveToLast(Chunks.pack(rx, rz), System.currentTimeMillis());
    }

    // ---- подписка ----

    public static void tick() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null) return;
        if (++ticks % 10 != 0) return;
        long now = System.currentTimeMillis();
        WANT.values().removeIf(t -> now - t > WANT_MS);
        String playerDim = p.level().dimension().location().toString();
        String target = !WANT.isEmpty() && !wantDim.equals(playerDim) ? wantDim : playerDim;
        int prx = Math.floorDiv(p.getBlockX(), 512), prz = Math.floorDiv(p.getBlockZ(), 512);
        List<Long> list = new ArrayList<>();
        LongOpenHashSet seen = new LongOpenHashSet();
        if (target.equals(playerDim)) {
            for (int dz = -1; dz <= 1; dz++) for (int dx = -1; dx <= 1; dx++) {
                long k = Chunks.pack(prx + dx, prz + dz);
                if (seen.add(k)) list.add(k);
            }
        }
        if (wantDim.equals(target) && !WANT.isEmpty()) {
            double fx, fz;
            if (target.equals(playerDim)) {
                fx = prx;
                fz = prz;
            } else {
                double sx = 0, sz = 0;
                for (long k : WANT.keySet()) {
                    sx += Chunks.x(k);
                    sz += Chunks.z(k);
                }
                fx = sx / WANT.size();
                fz = sz / WANT.size();
            }
            List<Long> wanted = new ArrayList<>(WANT.keySet());
            wanted.sort((a, b) -> Double.compare(dist(a, fx, fz), dist(b, fx, fz)));
            for (long k : wanted) if (seen.add(k)) list.add(k);
        }
        if (list.size() > ClaimsNet.MAX_REGIONS) list = list.subList(0, ClaimsNet.MAX_REGIONS);
        LongOpenHashSet next = new LongOpenHashSet();
        for (long k : list) next.add(k);
        if (target.equals(dimension) && next.equals(subscribed)) return;
        if (now - lastSubscribe < 1000 || !available()) return;
        lastSubscribe = now;
        if (!target.equals(dimension)) REGIONS.clear();
        else REGIONS.keySet().removeIf(k -> !next.contains(k));
        dimension = target;
        subscribed = next;
        counter++;
        long[] regions = new long[list.size()];
        for (int i = 0; i < regions.length; i++) regions[i] = list.get(i);
        send(new ClaimsNet.Subscribe(target, regions));
    }

    private static double dist(long region, double fx, double fz) {
        double dx = Chunks.x(region) - fx, dz = Chunks.z(region) - fz;
        return dx * dx + dz * dz;
    }

    // ---- правки ----

    /** Правка своей земли; больше 1024 чанков уходит несколькими пакетами. */
    public static void edit(String dim, int op, LongList chunks) {
        for (int i = 0; i < chunks.size(); i += ClaimsNet.MAX_EDIT) {
            LongList part = chunks.subList(i, Math.min(chunks.size(), i + ClaimsNet.MAX_EDIT));
            send(new ClaimsNet.Edit(dim, op, part.toLongArray()));
        }
    }

    /** Занять чанк под ногами (клавиша без назначения по умолчанию). */
    public static void claimHere() {
        LocalPlayer p = Minecraft.getInstance().player;
        if (p == null || !available()) return;
        LongArrayList one = new LongArrayList();
        one.add(Chunks.pack(p.getBlockX() >> 4, p.getBlockZ() >> 4));
        edit(p.level().dimension().location().toString(), ClaimsNet.OP_CLAIM, one);
    }

    public static void send(CustomPacketPayload payload) {
        ClientPacketListener c = Minecraft.getInstance().getConnection();
        if (c != null && c.hasChannel(payload.type())) PacketDistributor.sendToServer(payload);
    }

    /** Выход из мира. */
    public static void reset() {
        TEAMS.clear();
        REGIONS.clear();
        subscribed = new LongOpenHashSet();
        dimension = "";
        WANT.clear();
        wantDim = "";
        hello = null;
        result = null;
        counter++;
        lastSubscribe = 0;
    }
}

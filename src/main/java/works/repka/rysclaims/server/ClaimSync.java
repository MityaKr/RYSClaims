package works.repka.rysclaims.server;

import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;
import works.repka.ryscore.team.TeamsApi;
import works.repka.rysclaims.data.ClaimData;
import works.repka.rysclaims.data.Limits;
import works.repka.rysclaims.data.Rights;
import works.repka.rysclaims.data.TeamLand;
import works.repka.rysclaims.net.ClaimsNet;
import works.repka.rysclaims.net.RegionBytes;

/**
 * Земля для клиентов (план, раздел 9.2, «Сеть»). Игрок подписывается на измерение и до {@link ClaimsNet#MAX_REGIONS}
 * регионов 32×32 чанка, не чаще раза в секунду (чаще ждёт своей очереди). Новый регион уходит целиком ({@code Region}),
 * дальше только изменившиеся чанки ({@code Diff}) и только подписчикам этого региона. Правки чанков приходят от
 * {@link ClaimData.Changes} и копятся до конца тика: так тысяча чанков одной правки уходит одним пакетом.
 *
 * <p>Своя команда ({@code Hello}) и «что можно тебе на чужой земле» ({@code Access}) сверяются раз в секунду с тем, что
 * игрок уже получил, и уходят только при разнице; после правки команды сверка идёт на следующем тике. Так любая смена
 * прав, ролей, союзов, доверенных и режима оператора доходит без отдельных событий.
 *
 * <p>Всё в главном потоке сервера: обработчики пакетов NeoForge зовутся в нём.
 */
public final class ClaimSync {
    /** Сколько изменившихся чанков региона ещё идут пакетом {@code Diff}; больше уходит регионом целиком. */
    private static final int DIFF_MAX = 256;
    /** Правок от одного игрока в секунду. */
    private static final int EDITS_PER_SECOND = 5;

    private static final class Viewer {
        String dimension = "";
        LongOpenHashSet regions = new LongOpenHashSet();
        long lastSubscribe;
        ClaimsNet.@Nullable Subscribe pending;
        /**
         * Пока подписка ждёт очереди: регионы, которые есть у клиента во всех промежуточных подписках. Клиент выбрасывает
         * регион, выпавший из его подписки, поэтому склеенные подписки обязаны дослать то, что выпадало хоть раз.
         */
        @Nullable LongOpenHashSet held;
        ClaimsNet.@Nullable Hello hello;
        /** Команды, о которых игрок знает, и что ему на их земле можно. */
        final Object2IntOpenHashMap<UUID> access = new Object2IntOpenHashMap<>();
        long editSecond;
        int edits;
    }

    private static final Map<UUID, Viewer> VIEWERS = new HashMap<>();
    private static final Map<String, LongOpenHashSet> CHANGED = new HashMap<>();
    private static final Set<UUID> TOUCHED = new HashSet<>();
    private static boolean all;
    private static int ticks;
    /**
     * Версия прав (план 9.2, «Гашение нажатия»): растёт при любой перемене, от которой зависит, что игроку можно на чужой
     * земле (права, переключатели, доверенные, роли, союзы, состав команды, режим оператора). После роста все подписчики
     * на ближайшем тике получают {@code Hello} и {@code Access} по всем известным им командам с новой версией: клиент
     * гасит нажатие только по записи не старше версии своего {@code Hello}.
     */
    private static int epoch = 1;
    private static boolean accessDirty;

    private ClaimSync() {}

    /** Земля сервера поднялась: слушаем её правки. */
    public static void attach(ClaimData data) {
        data.listen(new ClaimData.Changes() {
            @Override
            public void chunk(String dimension, long chunk) {
                if (!VIEWERS.isEmpty()) CHANGED.computeIfAbsent(dimension, d -> new LongOpenHashSet()).add(chunk);
            }

            @Override
            public void all() {
                resendAll();
            }
        });
    }

    public static void clear() {
        VIEWERS.clear();
        CHANGED.clear();
        TOUCHED.clear();
        all = false;
        accessDirty = false;
    }

    /** Права на чьей-то земле могли смениться: версия прав растёт, рассылка на ближайшем тике. */
    public static void accessChanged() {
        if (!accessDirty) epoch++;
        accessDirty = true;
    }

    /** Текущая версия прав (для проверок). */
    public static int epoch() {
        return epoch;
    }

    /** Имена или цвета команд могли смениться: всем подписчикам их регионы заново. */
    public static void resendAll() {
        all = true;
    }

    /** У команды что-то поменялось: её участникам сверить {@code Hello} на ближайшем тике. */
    public static void touch(UUID team) {
        TOUCHED.add(team);
    }

    public static void login(ServerPlayer player) {
        if (!ClaimsNet.accepts(player) || !ClaimsServer.running()) return;
        VIEWERS.put(player.getUUID(), new Viewer());
        sendHello(ClaimsServer.service(), player, VIEWERS.get(player.getUUID()), true);
    }

    public static void logout(UUID player) {
        VIEWERS.remove(player);
    }

    // ---- тик ----

    public static void tick(MinecraftServer server, ClaimService s) {
        ticks++;
        if (VIEWERS.isEmpty()) {
            CHANGED.clear();
            TOUCHED.clear();
            all = false;
            accessDirty = false;
            return;
        }
        long now = System.currentTimeMillis();
        for (Map.Entry<UUID, Viewer> e : VIEWERS.entrySet()) {
            Viewer v = e.getValue();
            if (v.pending != null && now - v.lastSubscribe >= 1000) {
                ServerPlayer p = server.getPlayerList().getPlayer(e.getKey());
                ClaimsNet.Subscribe sub = v.pending;
                v.pending = null;
                if (p != null) apply(s, p, v, sub, now);
            }
        }
        if (all) {
            all = false;
            CHANGED.clear();
            for (Map.Entry<UUID, Viewer> e : VIEWERS.entrySet()) {
                ServerPlayer p = server.getPlayerList().getPlayer(e.getKey());
                if (p == null) continue;
                for (long r : e.getValue().regions) sendRegion(s, p, e.getValue(), e.getValue().dimension, r);
            }
        } else if (!CHANGED.isEmpty()) {
            flush(server, s);
        }
        boolean second = ticks % 20 == 0;
        boolean access = accessDirty;
        accessDirty = false;
        if (second || access || !TOUCHED.isEmpty()) {
            for (Map.Entry<UUID, Viewer> e : VIEWERS.entrySet()) {
                ServerPlayer p = server.getPlayerList().getPlayer(e.getKey());
                if (p == null) continue;
                if (second || access || TOUCHED.contains(s.teams().teamOf(p.getUUID()))) sendHello(s, p, e.getValue(), false);
                if (second || access) sendAccess(s, p, e.getValue(), access);
            }
            TOUCHED.clear();
        }
    }

    private static void flush(MinecraftServer server, ClaimService s) {
        for (Map.Entry<UUID, Viewer> e : VIEWERS.entrySet()) {
            Viewer v = e.getValue();
            LongOpenHashSet changed = CHANGED.get(v.dimension);
            if (changed == null || v.regions.isEmpty()) continue;
            ServerPlayer p = server.getPlayerList().getPlayer(e.getKey());
            if (p == null) continue;
            Long2ObjectLinkedOpenHashMap<LongList> byRegion = new Long2ObjectLinkedOpenHashMap<>();
            for (long chunk : changed) {
                long r = RegionBytes.regionOf(chunk);
                if (v.regions.contains(r)) byRegion.computeIfAbsent(r, k -> new LongArrayList()).add(chunk);
            }
            DiffBuilder diff = new DiffBuilder(s, p, v);
            for (var r : byRegion.long2ObjectEntrySet()) {
                if (r.getValue().size() > DIFF_MAX) sendRegion(s, p, v, v.dimension, r.getLongKey());
                else for (long chunk : r.getValue()) diff.add(chunk);
            }
            diff.send();
        }
        CHANGED.clear();
    }

    /** Пакет {@code Diff} одному игроку; палитра пакета не больше 63 команд, иначе уходит следующим пакетом. */
    private static final class DiffBuilder {
        final ClaimService s;
        final ServerPlayer p;
        final Viewer v;
        final Map<UUID, Integer> index = new LinkedHashMap<>();
        final List<ClaimsNet.TeamEntry> palette = new ArrayList<>();
        final LongArrayList chunks = new LongArrayList();
        final java.io.ByteArrayOutputStream codes = new java.io.ByteArrayOutputStream();

        DiffBuilder(ClaimService s, ServerPlayer p, Viewer v) {
            this.s = s;
            this.p = p;
            this.v = v;
        }

        void add(long chunk) {
            var c = s.data().get(v.dimension, chunk);
            if (c == null) {
                chunks.add(chunk);
                codes.write(0);
                return;
            }
            Integer i = index.get(c.team());
            if (i == null) {
                if (palette.size() >= RegionBytes.MAX_PALETTE) send();
                i = palette.size();
                index.put(c.team(), i);
                palette.add(entry(s, p, v, c.team()));
            }
            chunks.add(chunk);
            codes.write(RegionBytes.code(i, c.force(), c.releasing()));
        }

        void send() {
            if (chunks.isEmpty()) return;
            ClaimsNet.send(p, new ClaimsNet.Diff(v.dimension, epoch, List.copyOf(palette), chunks.toLongArray(), codes.toByteArray()));
            index.clear();
            palette.clear();
            chunks.clear();
            codes.reset();
        }
    }

    // ---- пакеты от клиента ----

    public static void subscribe(ServerPlayer player, ClaimsNet.Subscribe sub) {
        Viewer v = VIEWERS.get(player.getUUID());
        if (v == null || !ClaimsServer.running()) return;
        long now = System.currentTimeMillis();
        if (now - v.lastSubscribe < 1000) {
            if (v.held == null) v.held = new LongOpenHashSet(v.regions);
            if (!sub.dimension().equals(v.pending == null ? v.dimension : v.pending.dimension())) v.held.clear();
            LongOpenHashSet in = new LongOpenHashSet(sub.regions());
            v.held.removeIf(r -> !in.contains(r));
            v.pending = sub;
            return;
        }
        apply(ClaimsServer.service(), player, v, sub, now);
    }

    private static void apply(ClaimService s, ServerPlayer player, Viewer v, ClaimsNet.Subscribe sub, long now) {
        v.lastSubscribe = now;
        if (ResourceLocation.tryParse(sub.dimension()) == null) return;
        LongOpenHashSet next = new LongOpenHashSet();
        // больше 64 регионов не держим: лишние в конце списка отклоняются (клиент шлёт ближние первыми)
        for (long r : sub.regions()) {
            if (next.size() >= ClaimsNet.MAX_REGIONS) break;
            next.add(r);
        }
        boolean sameDim = sub.dimension().equals(v.dimension);
        LongOpenHashSet old = !sameDim ? new LongOpenHashSet() : v.held != null ? v.held : v.regions;
        v.held = null;
        v.dimension = sub.dimension();
        v.regions = next;
        for (long r : next) if (!old.contains(r)) sendRegion(s, player, v, v.dimension, r);
    }

    /** Сколько регионов держит подписка игрока (для проверок). */
    public static int subscribed(UUID player) {
        Viewer v = VIEWERS.get(player);
        return v == null ? -1 : v.regions.size();
    }

    public static void edit(ServerPlayer player, ClaimsNet.Edit edit) {
        Viewer v = VIEWERS.get(player.getUUID());
        if (v == null || !ClaimsServer.running()) return;
        long sec = System.currentTimeMillis() / 1000;
        if (v.editSecond != sec) {
            v.editSecond = sec;
            v.edits = 0;
        }
        if (++v.edits > EDITS_PER_SECOND) return;
        ClaimService s = ClaimsServer.service();
        ResourceLocation loc = ResourceLocation.tryParse(edit.dimension());
        if (loc == null || player.server.getLevel(ResourceKey.create(Registries.DIMENSION, loc)) == null) return;
        UUID id = player.getUUID();
        UUID team = s.teams().teamOf(id);
        LongArrayList chunks = new LongArrayList(edit.chunks());
        ClaimService.Result r = switch (edit.op()) {
            case ClaimsNet.OP_CLAIM -> s.claim(id, team, edit.dimension(), chunks, false);
            case ClaimsNet.OP_RELEASE -> s.release(id, team, edit.dimension(), chunks, false);
            case ClaimsNet.OP_LOAD -> s.load(id, team, edit.dimension(), chunks, true, false);
            case ClaimsNet.OP_UNLOAD -> s.load(id, team, edit.dimension(), chunks, false, false);
            default -> null;
        };
        if (r == null) return;
        touch(team);
        ClaimsNet.send(player, new ClaimsNet.EditResult(edit.op(), r.done, skipped(r)));
    }

    private static int[] skipped(ClaimService.Result r) {
        int[] out = new int[ClaimService.Skip.values().length];
        r.skipped.forEach((k, n) -> out[k.ordinal()] = n);
        return out;
    }

    public static void keep(ServerPlayer player) {
        if (!VIEWERS.containsKey(player.getUUID()) || !ClaimsServer.running()) return;
        ClaimService s = ClaimsServer.service();
        UUID team = s.teams().teamOf(player.getUUID());
        int n = s.keep(player.getUUID(), team, false);
        ClaimService.Result r = new ClaimService.Result();
        if (n < 0) r.skipped.put(ClaimService.Skip.NO_RIGHT, 1);
        else r.done = n;
        touch(team);
        ClaimsNet.send(player, new ClaimsNet.EditResult(ClaimsNet.OP_KEEP, r.done, skipped(r)));
    }

    public static void rights(ServerPlayer player, ClaimsNet.RightsEdit e) {
        if (!VIEWERS.containsKey(player.getUUID()) || !ClaimsServer.running()) return;
        ClaimService s = ClaimsServer.service();
        UUID id = player.getUUID();
        UUID team = s.teams().teamOf(id);
        ClaimService.Result r = new ClaimService.Result();
        if (!s.editsRights(id, team, s.bypass(id))) {
            r.skipped.put(s.settings().teamsEditRights ? ClaimService.Skip.NO_RIGHT : ClaimService.Skip.LOCKED, 1);
            ClaimsNet.send(player, new ClaimsNet.EditResult(ClaimsNet.OP_RIGHTS, 0, skipped(r)));
            return;
        }
        Rights.Action[] actions = Rights.Action.values();
        switch (e.kind()) {
            case ClaimsNet.RIGHTS_CELL -> {
                if (e.action() < 0 || e.action() >= actions.length || e.column() < 0 || e.column() >= Rights.Column.values().length) return;
                s.toggleRight(team, actions[e.action()], Rights.Column.values()[e.column()], e.on());
            }
            case ClaimsNet.RIGHTS_PRESET -> {
                if (e.value() < 0 || e.value() >= Rights.Preset.values().length) return;
                s.applyPreset(team, Rights.Preset.values()[e.value()]);
            }
            case ClaimsNet.RIGHTS_TABLE -> s.setRights(team, Rights.fromBits(e.value()));
            default -> {
                return;
            }
        }
        touch(team);
        r.done = 1;
        ClaimsNet.send(player, new ClaimsNet.EditResult(ClaimsNet.OP_RIGHTS, 1, skipped(r)));
    }

    // ---- что уходит ----

    private static void sendRegion(ClaimService s, ServerPlayer p, Viewer v, String dim, long region) {
        int rx = works.repka.rysclaims.data.Chunks.x(region), rz = works.repka.rysclaims.data.Chunks.z(region);
        RegionBytes.Built b = RegionBytes.build(chunk -> s.data().get(dim, chunk), rx, rz);
        List<ClaimsNet.TeamEntry> palette = new ArrayList<>(b.palette().size());
        for (UUID team : b.palette()) palette.add(entry(s, p, v, team));
        ClaimsNet.send(p, new ClaimsNet.Region(dim, rx, rz, epoch, palette, RegionBytes.encode(b.codes())));
    }

    private static ClaimsNet.TeamEntry entry(ClaimService s, ServerPlayer p, Viewer v, UUID team) {
        int bits = access(s, p.getUUID(), team);
        v.access.put(team, bits);
        return new ClaimsNet.TeamEntry(team, Commands.teamName(s, team).getString(), 0xFF000000 | TeamsApi.get().color(team), bits);
    }

    /** Что можно игроку на земле команды: биты {@code ClaimsNet.BUILD} и дальше по порядку {@code Rights.Action}. */
    static int access(ClaimService s, UUID player, UUID team) {
        int bits = 0;
        for (Rights.Action a : Rights.Action.values()) if (s.allows(player, team, a)) bits |= 1 << a.ordinal();
        return bits;
    }

    /** Что изменилось в правах игрока; {@code all}: все известные ему команды (версия прав выросла, записи надо освежить). */
    private static void sendAccess(ClaimService s, ServerPlayer p, Viewer v, boolean all) {
        if (v.access.isEmpty()) return;
        List<UUID> teams = new ArrayList<>();
        java.io.ByteArrayOutputStream bits = new java.io.ByteArrayOutputStream();
        for (var e : v.access.object2IntEntrySet()) {
            int now = access(s, p.getUUID(), e.getKey());
            if (now == e.getIntValue() && !all) continue;
            e.setValue(now);
            teams.add(e.getKey());
            bits.write(now);
            if (teams.size() >= 4096) break;
        }
        if (!teams.isEmpty()) ClaimsNet.send(p, new ClaimsNet.Access(epoch, teams, bits.toByteArray()));
    }

    static ClaimsNet.Hello hello(ClaimService s, UUID id) {
        TeamsApi api = TeamsApi.get();
        UUID team = s.teams().teamOf(id);
        Limits lim = s.limits(team);
        long[] rel = s.releasing(team);
        TeamLand land = s.data().land(team);
        Rights rights = land == null ? s.settings().defaultRights : land.rights;
        boolean admin = s.bypass(id);
        return new ClaimsNet.Hello(team, Commands.teamName(s, team).getString(), 0xFF000000 | api.color(team), api.isSolo(team),
            s.teams().roleOf(id), s.teams().members(team).size(),
            s.data().claimed(team), lim.claims, s.data().forced(team), lim.force, (int) rel[0], rel[1],
            s.manages(id, team, ClaimService.PERM_CLAIM), s.manages(id, team, ClaimService.PERM_FORCE),
            s.editsRights(id, team, admin), !s.settings().teamsEditRights, rights.bits(), land == null ? 0 : land.trusted.size(),
            s.settings().releaseSeconds, admin, epoch);
    }

    private static void sendHello(ClaimService s, ServerPlayer p, Viewer v, boolean force) {
        ClaimsNet.Hello h = hello(s, p.getUUID());
        // в сравнении момент освобождения на часах сервера, в пакете сколько до него осталось
        if (!force && h.equals(v.hello)) return;
        v.hello = h;
        long left = h.releasing() == 0 ? 0 : Math.max(0, h.releaseIn() - System.currentTimeMillis());
        ClaimsNet.send(p, h.withReleaseIn(left));
    }
}

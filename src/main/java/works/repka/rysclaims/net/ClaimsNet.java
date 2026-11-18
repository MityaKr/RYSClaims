package works.repka.rysclaims.net;

import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.DecoderException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import works.repka.rysclaims.RYSClaims;
import works.repka.rysclaims.server.ClaimSync;

/**
 * Сеть RYSClaims (план, раздел 9.2), канал необязательный: клиент без мода не отваливается, сервер шлёт ему только строки.
 *
 * <p>Клиенту: {@link Hello} (своя команда, лимиты, права, роль; шлётся при входе и при каждой перемене),
 * {@link Region} (регион 32×32 чанка целиком), {@link Diff} (изменившиеся чанки, только подписчикам региона),
 * {@link Access} (что можно этому игроку на земле команды, при смене прав), {@link EditResult}, {@link Denied}.
 * Серверу: {@link Subscribe} (измерение и до 64 регионов), {@link Edit} (до 1024 чанков), {@link Keep},
 * {@link RightsEdit}. Сервер клиенту не верит: каждая правка проверяется заново.
 */
public final class ClaimsNet {
    /** Сколько регионов держит подписка и сколько чанков в одной правке. */
    public static final int MAX_REGIONS = 64, MAX_EDIT = 1024;
    /** Бит действий в {@link TeamEntry#access()}: строить, открывать, двери, животные (порядок {@code Rights.Action}). */
    public static final int BUILD = 1, OPEN = 2, USE = 4, ENTITY = 8;

    private ClaimsNet() {}

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(RYSClaims.MODID, path);
    }

    private static String dim(FriendlyByteBuf buf) {
        return buf.readUtf(128);
    }

    private static long[] longs(FriendlyByteBuf buf, int max) {
        int n = buf.readVarInt();
        if (n < 0 || n > max) throw new DecoderException("слишком длинный список: " + n);
        long[] out = new long[n];
        for (int i = 0; i < n; i++) out[i] = buf.readLong();
        return out;
    }

    private static void longs(FriendlyByteBuf buf, long[] v) {
        buf.writeVarInt(v.length);
        for (long l : v) buf.writeLong(l);
    }

    /** Команда в палитре региона: имя, цвет и что можно получателю на её земле. */
    public record TeamEntry(UUID id, String name, int color, int access) {
        static TeamEntry read(FriendlyByteBuf buf) {
            return new TeamEntry(buf.readUUID(), buf.readUtf(64), buf.readInt(), buf.readByte());
        }

        void write(FriendlyByteBuf buf) {
            buf.writeUUID(id);
            buf.writeUtf(name.length() > 64 ? name.substring(0, 64) : name, 64);
            buf.writeInt(color);
            buf.writeByte(access);
        }
    }

    private static List<TeamEntry> readPalette(FriendlyByteBuf buf) {
        int n = buf.readVarInt();
        if (n < 0 || n > 4096) throw new DecoderException("слишком большая палитра: " + n);
        List<TeamEntry> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) out.add(TeamEntry.read(buf));
        return out;
    }

    private static void writePalette(FriendlyByteBuf buf, List<TeamEntry> p) {
        buf.writeVarInt(p.size());
        for (TeamEntry e : p) e.write(buf);
    }

    // ---- клиенту ----

    /**
     * Своя команда: имя, цвет, роль, лимиты, освобождение, права управления и таблица прав доступа ({@code Rights.bits}).
     * {@code releaseIn} миллисекунды до первого освобождения на момент отправки.
     */
    public record Hello(UUID team, String name, int color, boolean solo, String role, int members,
                        int claimed, int claimLimit, int forced, int forceLimit, int releasing, long releaseIn,
                        boolean canClaim, boolean canForce, boolean canRights, boolean rightsLocked, int rights, int trusted,
                        int releaseSeconds, boolean bypass, int epoch) implements CustomPacketPayload {
        public static final Type<Hello> TYPE = new Type<>(id("hello"));
        public static final StreamCodec<FriendlyByteBuf, Hello> CODEC = CustomPacketPayload.codec(Hello::write, Hello::read);

        private static Hello read(FriendlyByteBuf b) {
            return new Hello(b.readUUID(), b.readUtf(64), b.readInt(), b.readBoolean(), b.readUtf(16), b.readVarInt(),
                b.readVarInt(), b.readVarInt(), b.readVarInt(), b.readVarInt(), b.readVarInt(), b.readVarLong(),
                b.readBoolean(), b.readBoolean(), b.readBoolean(), b.readBoolean(), b.readInt(), b.readVarInt(),
                b.readVarInt(), b.readBoolean(), b.readVarInt());
        }

        private void write(FriendlyByteBuf b) {
            b.writeUUID(team);
            b.writeUtf(name.length() > 64 ? name.substring(0, 64) : name, 64);
            b.writeInt(color);
            b.writeBoolean(solo);
            b.writeUtf(role, 16);
            b.writeVarInt(members);
            b.writeVarInt(claimed);
            b.writeVarInt(claimLimit);
            b.writeVarInt(forced);
            b.writeVarInt(forceLimit);
            b.writeVarInt(releasing);
            b.writeVarLong(releaseIn);
            b.writeBoolean(canClaim);
            b.writeBoolean(canForce);
            b.writeBoolean(canRights);
            b.writeBoolean(rightsLocked);
            b.writeInt(rights);
            b.writeVarInt(trusted);
            b.writeVarInt(releaseSeconds);
            b.writeBoolean(bypass);
            b.writeVarInt(epoch);
        }

        public Hello withReleaseIn(long ms) {
            return new Hello(team, name, color, solo, role, members, claimed, claimLimit, forced, forceLimit, releasing, ms,
                canClaim, canForce, canRights, rightsLocked, rights, trusted, releaseSeconds, bypass, epoch);
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Регион целиком: палитра и байты {@link RegionBytes#encode}. */
    public record Region(String dimension, int rx, int rz, int epoch, List<TeamEntry> palette, byte[] data) implements CustomPacketPayload {
        public static final Type<Region> TYPE = new Type<>(id("region"));
        public static final StreamCodec<FriendlyByteBuf, Region> CODEC = CustomPacketPayload.codec(Region::write, Region::read);

        private static Region read(FriendlyByteBuf b) {
            return new Region(dim(b), b.readVarInt(), b.readVarInt(), b.readVarInt(), readPalette(b), b.readByteArray(RegionBytes.SIZE + 1));
        }

        private void write(FriendlyByteBuf b) {
            b.writeUtf(dimension, 128);
            b.writeVarInt(rx);
            b.writeVarInt(rz);
            b.writeVarInt(epoch);
            writePalette(b, palette);
            b.writeByteArray(data);
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Изменившиеся чанки: байт чанка ссылается на палитру этого пакета. */
    public record Diff(String dimension, int epoch, List<TeamEntry> palette, long[] chunks, byte[] codes) implements CustomPacketPayload {
        public static final Type<Diff> TYPE = new Type<>(id("diff"));
        public static final StreamCodec<FriendlyByteBuf, Diff> CODEC = CustomPacketPayload.codec(Diff::write, Diff::read);

        private static Diff read(FriendlyByteBuf b) {
            String d = dim(b);
            int epoch = b.readVarInt();
            List<TeamEntry> p = readPalette(b);
            long[] c = longs(b, RegionBytes.SIZE * MAX_REGIONS);
            byte[] codes = b.readByteArray(c.length);
            if (codes.length != c.length) throw new DecoderException("байтов не столько, сколько чанков");
            return new Diff(d, epoch, p, c, codes);
        }

        private void write(FriendlyByteBuf b) {
            b.writeUtf(dimension, 128);
            b.writeVarInt(epoch);
            writePalette(b, palette);
            longs(b, chunks);
            b.writeByteArray(codes);
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * Что можно получателю на земле этих команд: биты {@link #BUILD} и дальше. {@code epoch} это версия прав сервера, на
     * которой посчитаны биты: клиент гасит нажатие, только если версия его записи не старше версии из {@link Hello}.
     */
    public record Access(int epoch, List<UUID> teams, byte[] bits) implements CustomPacketPayload {
        public static final Type<Access> TYPE = new Type<>(id("access"));
        public static final StreamCodec<FriendlyByteBuf, Access> CODEC = CustomPacketPayload.codec(Access::write, Access::read);

        private static Access read(FriendlyByteBuf b) {
            int epoch = b.readVarInt();
            int n = b.readVarInt();
            if (n < 0 || n > 4096) throw new DecoderException("слишком много команд: " + n);
            List<UUID> t = new ArrayList<>(n);
            for (int i = 0; i < n; i++) t.add(b.readUUID());
            byte[] bits = b.readByteArray(n);
            return new Access(epoch, t, bits);
        }

        private void write(FriendlyByteBuf b) {
            b.writeVarInt(epoch);
            b.writeVarInt(teams.size());
            for (UUID u : teams) b.writeUUID(u);
            b.writeByteArray(bits);
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Итог правки: сколько сделано и сколько пропущено по причинам ({@code ClaimService.Skip} по порядку). */
    public record EditResult(int op, int done, int[] skipped) implements CustomPacketPayload {
        public static final Type<EditResult> TYPE = new Type<>(id("edit_result"));
        public static final StreamCodec<FriendlyByteBuf, EditResult> CODEC = CustomPacketPayload.codec(EditResult::write, EditResult::read);

        private static EditResult read(FriendlyByteBuf b) {
            int op = b.readVarInt(), done = b.readVarInt();
            int[] s = b.readVarIntArray(32);
            return new EditResult(op, done, s);
        }

        private void write(FriendlyByteBuf b) {
            b.writeVarInt(op);
            b.writeVarInt(done);
            b.writeVarIntArray(skipped);
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * Отказ. {@code reason} это ключ {@code ClaimService.Reason}, {@code owner} имя хозяина земли (пусто, если земля скрыта
     * от этого игрока или ничья).
     */
    public record Denied(String reason, String owner) implements CustomPacketPayload {
        public static final Type<Denied> TYPE = new Type<>(id("denied"));
        public static final StreamCodec<ByteBuf, Denied> CODEC = StreamCodec.composite(
            ByteBufCodecs.stringUtf8(32), Denied::reason, ByteBufCodecs.stringUtf8(128), Denied::owner, Denied::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Доверенный игрок: UUID и ник. */
    public record Trusted(UUID id, String name) {}

    /**
     * Участок своей команды: номер, имя (пусто: «Участок N»), измерение, сколько прогружено, когда занят первый чанк и кем,
     * все чанки (по ним окно считает «Соединит участки» и «Разделит участок» до подтверждения).
     */
    public record PlotInfo(int id, String name, String dim, int forced, long at, String by, long[] chunks) {}

    /**
     * Земля своей команды для окна (план 9.2, {@code LandState}): переключатели (бит на {@code TeamLand.Flag}), закреплённые
     * сервером (маска и значения), видимость, режим боя сервера, доверенные, участки. Шлётся при входе и после перемены.
     */
    public record LandState(UUID team, int flags, int lockMask, int lockValues, int visibility, int pvpMode, List<Trusted> trusted,
                            List<PlotInfo> plots) implements CustomPacketPayload {
        public static final Type<LandState> TYPE = new Type<>(id("land_state"));
        public static final StreamCodec<FriendlyByteBuf, LandState> CODEC = CustomPacketPayload.codec(LandState::write, LandState::read);
        /** Сколько чанков участков уходит самое большее (дальше участки приходят без чанков). */
        public static final int MAX_CHUNKS = 65536;

        private static LandState read(FriendlyByteBuf b) {
            UUID team = b.readUUID();
            int flags = b.readVarInt(), mask = b.readVarInt(), values = b.readVarInt(), vis = b.readVarInt(), pvp = b.readVarInt();
            int n = b.readVarInt();
            if (n < 0 || n > 1024) throw new DecoderException("слишком много доверенных: " + n);
            List<Trusted> trusted = new ArrayList<>(n);
            for (int i = 0; i < n; i++) trusted.add(new Trusted(b.readUUID(), b.readUtf(32)));
            int m = b.readVarInt();
            if (m < 0 || m > 65536) throw new DecoderException("слишком много участков: " + m);
            List<PlotInfo> plots = new ArrayList<>(m);
            for (int i = 0; i < m; i++) {
                plots.add(new PlotInfo(b.readVarInt(), b.readUtf(64), dim(b), b.readVarInt(), b.readVarLong(), b.readUtf(32), longs(b, MAX_CHUNKS)));
            }
            return new LandState(team, flags, mask, values, vis, pvp, trusted, plots);
        }

        private void write(FriendlyByteBuf b) {
            b.writeUUID(team);
            b.writeVarInt(flags);
            b.writeVarInt(lockMask);
            b.writeVarInt(lockValues);
            b.writeVarInt(visibility);
            b.writeVarInt(pvpMode);
            b.writeVarInt(trusted.size());
            for (Trusted t : trusted) {
                b.writeUUID(t.id());
                b.writeUtf(t.name().length() > 32 ? t.name().substring(0, 32) : t.name(), 32);
            }
            b.writeVarInt(plots.size());
            int budget = MAX_CHUNKS;
            for (PlotInfo p : plots) {
                b.writeVarInt(p.id());
                b.writeUtf(p.name(), 64);
                b.writeUtf(p.dim(), 128);
                b.writeVarInt(p.forced());
                b.writeVarLong(p.at());
                b.writeUtf(p.by().length() > 32 ? p.by().substring(0, 32) : p.by(), 32);
                long[] c = p.chunks().length <= budget ? p.chunks() : new long[0];
                budget -= c.length;
                longs(b, c);
            }
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Хозяевам в сети: чужой пытался что-то на земле команды (тост {@code 06b}). */
    public record OwnerAlert(String name, String reason, String plot) implements CustomPacketPayload {
        public static final Type<OwnerAlert> TYPE = new Type<>(ClaimsNet.id("owner_alert"));
        public static final StreamCodec<ByteBuf, OwnerAlert> CODEC = StreamCodec.composite(
            ByteBufCodecs.stringUtf8(64), OwnerAlert::name, ByteBufCodecs.stringUtf8(32), OwnerAlert::reason,
            ByteBufCodecs.stringUtf8(64), OwnerAlert::plot, OwnerAlert::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Строка журнала отказов: когда (последний и первый раз), сколько раз, кто, машина ли, почему, где, участок. */
    public record LogLine(long last, long first, int count, String name, boolean machine, String reason, String dim, int x, int y, int z, String plot) {
        static LogLine read(FriendlyByteBuf b) {
            return new LogLine(b.readVarLong(), b.readVarLong(), b.readVarInt(), b.readUtf(64), b.readBoolean(), b.readUtf(32), ClaimsNet.dim(b),
                b.readVarInt(), b.readVarInt(), b.readVarInt(), b.readUtf(64));
        }

        void write(FriendlyByteBuf b) {
            b.writeVarLong(last);
            b.writeVarLong(first);
            b.writeVarInt(count);
            b.writeUtf(name.length() > 64 ? name.substring(0, 64) : name, 64);
            b.writeBoolean(machine);
            b.writeUtf(reason, 32);
            b.writeUtf(dim, 128);
            b.writeVarInt(x);
            b.writeVarInt(y);
            b.writeVarInt(z);
            b.writeUtf(plot.length() > 64 ? plot.substring(0, 64) : plot, 64);
        }
    }

    /** Журнал своей земли (макет {@code 43}): настройки ленты и тоста, можно ли их править, срок хранения, последние строки. */
    public record LogPage(boolean feed, boolean toast, boolean canEdit, int days, List<LogLine> lines) implements CustomPacketPayload {
        public static final Type<LogPage> TYPE = new Type<>(ClaimsNet.id("log_page"));
        public static final StreamCodec<FriendlyByteBuf, LogPage> CODEC = CustomPacketPayload.codec(LogPage::write, LogPage::read);
        public static final int MAX = 200;

        private static LogPage read(FriendlyByteBuf b) {
            boolean feed = b.readBoolean(), toast = b.readBoolean(), edit = b.readBoolean();
            int days = b.readVarInt(), n = b.readVarInt();
            if (n < 0 || n > MAX) throw new DecoderException("слишком длинный журнал: " + n);
            List<LogLine> lines = new ArrayList<>(n);
            for (int i = 0; i < n; i++) lines.add(LogLine.read(b));
            return new LogPage(feed, toast, edit, days, lines);
        }

        private void write(FriendlyByteBuf b) {
            b.writeBoolean(feed);
            b.writeBoolean(toast);
            b.writeBoolean(canEdit);
            b.writeVarInt(days);
            int n = Math.min(lines.size(), MAX);
            b.writeVarInt(n);
            for (int i = 0; i < n; i++) lines.get(i).write(b);
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Окно просит журнал своей земли. */
    public record LogRequest() implements CustomPacketPayload {
        public static final LogRequest INSTANCE = new LogRequest();
        public static final Type<LogRequest> TYPE = new Type<>(ClaimsNet.id("log_request"));
        public static final StreamCodec<FriendlyByteBuf, LogRequest> CODEC = StreamCodec.unit(INSTANCE);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * Вопрос игроку уведомлением с двумя кнопками: {@code kind} 0 «Отдать личную землю команде?» при вступлении (макет 45),
     * 1 «Забрать свои чанки?» при уходе; сколько чанков, хватает ли лимита, имя команды.
     */
    public record Question(int kind, int count, boolean fits, String team) implements CustomPacketPayload {
        public static final Type<Question> TYPE = new Type<>(ClaimsNet.id("question"));
        public static final StreamCodec<ByteBuf, Question> CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, Question::kind, ByteBufCodecs.VAR_INT, Question::count, ByteBufCodecs.BOOL, Question::fits,
            ByteBufCodecs.stringUtf8(64), Question::team, Question::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Ответ на {@link Question}. */
    public record Answer(int kind, boolean yes) implements CustomPacketPayload {
        public static final Type<Answer> TYPE = new Type<>(ClaimsNet.id("answer"));
        public static final StreamCodec<ByteBuf, Answer> CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, Answer::kind, ByteBufCodecs.BOOL, Answer::yes, Answer::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    // ---- серверу ----

    /** Подписка: измерение и регионы (упакованы как чанк). Больше {@link #MAX_REGIONS} сервер не держит. */
    public record Subscribe(String dimension, long[] regions) implements CustomPacketPayload {
        public static final Type<Subscribe> TYPE = new Type<>(id("subscribe"));
        public static final StreamCodec<FriendlyByteBuf, Subscribe> CODEC = CustomPacketPayload.codec(Subscribe::write, Subscribe::read);

        private static Subscribe read(FriendlyByteBuf b) {
            return new Subscribe(dim(b), longs(b, 256));
        }

        private void write(FriendlyByteBuf b) {
            b.writeUtf(dimension, 128);
            longs(b, regions);
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public static final int OP_CLAIM = 0, OP_RELEASE = 1, OP_LOAD = 2, OP_UNLOAD = 3, OP_KEEP = 4, OP_RIGHTS = 5;

    /** Правка земли своей команды: действие {@code OP_*} и до {@link #MAX_EDIT} чанков. */
    public record Edit(String dimension, int op, long[] chunks) implements CustomPacketPayload {
        public static final Type<Edit> TYPE = new Type<>(id("edit"));
        public static final StreamCodec<FriendlyByteBuf, Edit> CODEC = CustomPacketPayload.codec(Edit::write, Edit::read);

        private static Edit read(FriendlyByteBuf b) {
            return new Edit(dim(b), b.readVarInt(), longs(b, MAX_EDIT));
        }

        private void write(FriendlyByteBuf b) {
            b.writeUtf(dimension, 128);
            b.writeVarInt(op);
            longs(b, chunks);
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Оставить: отменить все идущие освобождения своей команды. */
    public record Keep() implements CustomPacketPayload {
        public static final Keep INSTANCE = new Keep();
        public static final Type<Keep> TYPE = new Type<>(id("keep"));
        public static final StreamCodec<FriendlyByteBuf, Keep> CODEC = StreamCodec.unit(INSTANCE);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public static final int RIGHTS_CELL = 0, RIGHTS_PRESET = 1, RIGHTS_TABLE = 2, RIGHTS_ROLE = 3;

    /**
     * Правка прав доступа своей команды: клетка окна ({@code action}, {@code column} по {@code Rights.Column}, {@code on}),
     * готовый набор ({@code value} номер {@code Rights.Preset}) или вся таблица ({@code value} это {@code Rights.bits}).
     */
    public record RightsEdit(int kind, int action, int column, boolean on, int value) implements CustomPacketPayload {
        public static final Type<RightsEdit> TYPE = new Type<>(id("rights_edit"));
        public static final StreamCodec<FriendlyByteBuf, RightsEdit> CODEC = CustomPacketPayload.codec(RightsEdit::write, RightsEdit::read);

        private static RightsEdit read(FriendlyByteBuf b) {
            return new RightsEdit(b.readVarInt(), b.readVarInt(), b.readVarInt(), b.readBoolean(), b.readInt());
        }

        private void write(FriendlyByteBuf b) {
            b.writeVarInt(kind);
            b.writeVarInt(action);
            b.writeVarInt(column);
            b.writeBoolean(on);
            b.writeInt(value);
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public static final int LAND_TRUST = 0, LAND_UNTRUST = 1, LAND_FLAG = 2, LAND_VISIBILITY = 3, LAND_RENAME = 4, LAND_UNLOAD_PLOT = 5,
        LAND_RELEASE_PLOT = 6, LAND_LOG_FEED = 7, LAND_LOG_TOAST = 8;
    /** Итог {@link LandEdit} приходит {@link EditResult} с действием {@code OP_LAND + kind}. */
    public static final int OP_LAND = 10;

    /**
     * Правка земли своей команды из окна: доверенный по нику ({@code text}) или UUID ({@code text}), переключатель
     * ({@code id} номер {@code TeamLand.Flag}, {@code on}), видимость ({@code id}), участок ({@code id} номер, {@code text} имя).
     */
    public record LandEdit(int kind, int id, boolean on, String text) implements CustomPacketPayload {
        public static final Type<LandEdit> TYPE = new Type<>(ClaimsNet.id("land_edit"));
        public static final StreamCodec<FriendlyByteBuf, LandEdit> CODEC = CustomPacketPayload.codec(LandEdit::write, LandEdit::read);

        private static LandEdit read(FriendlyByteBuf b) {
            return new LandEdit(b.readVarInt(), b.readVarInt(), b.readBoolean(), b.readUtf(64));
        }

        private void write(FriendlyByteBuf b) {
            b.writeVarInt(kind);
            b.writeVarInt(id);
            b.writeBoolean(on);
            b.writeUtf(text.length() > 64 ? text.substring(0, 64) : text, 64);
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    // ---- регистрация ----

    public static void register(RegisterPayloadHandlersEvent event) {
        // optional: клиент без мода (или старый) не отваливается, сервер тогда шлёт строку над хотбаром
        PayloadRegistrar r = event.registrar("2").optional();
        r.playToClient(Denied.TYPE, Denied.CODEC, (p, c) -> client(() -> Client.denied(p)));
        r.playToClient(Hello.TYPE, Hello.CODEC, (p, c) -> client(() -> Client.hello(p)));
        r.playToClient(Region.TYPE, Region.CODEC, (p, c) -> client(() -> Client.region(p)));
        r.playToClient(Diff.TYPE, Diff.CODEC, (p, c) -> client(() -> Client.diff(p)));
        r.playToClient(Access.TYPE, Access.CODEC, (p, c) -> client(() -> Client.access(p)));
        r.playToClient(EditResult.TYPE, EditResult.CODEC, (p, c) -> client(() -> Client.result(p)));
        r.playToClient(LandState.TYPE, LandState.CODEC, (p, c) -> client(() -> Client.state(p)));
        r.playToClient(OwnerAlert.TYPE, OwnerAlert.CODEC, (p, c) -> client(() -> Client.alert(p)));
        r.playToClient(LogPage.TYPE, LogPage.CODEC, (p, c) -> client(() -> Client.log(p)));
        r.playToClient(Question.TYPE, Question.CODEC, (p, c) -> client(() -> Client.question(p)));
        r.playToServer(Subscribe.TYPE, Subscribe.CODEC, (p, c) -> server(c, pl -> ClaimSync.subscribe(pl, p)));
        r.playToServer(Edit.TYPE, Edit.CODEC, (p, c) -> server(c, pl -> ClaimSync.edit(pl, p)));
        r.playToServer(Keep.TYPE, Keep.CODEC, (p, c) -> server(c, ClaimSync::keep));
        r.playToServer(RightsEdit.TYPE, RightsEdit.CODEC, (p, c) -> server(c, pl -> ClaimSync.rights(pl, p)));
        r.playToServer(LandEdit.TYPE, LandEdit.CODEC, (p, c) -> server(c, pl -> ClaimSync.land(pl, p)));
        r.playToServer(LogRequest.TYPE, LogRequest.CODEC, (p, c) -> server(c, ClaimSync::log));
        r.playToServer(Answer.TYPE, Answer.CODEC, (p, c) -> server(c, pl -> works.repka.rysclaims.server.Membership.onAnswer(pl, p)));
    }

    private static void client(Runnable r) {
        if (FMLEnvironment.dist.isClient()) r.run();
    }

    private static void server(IPayloadContext c, java.util.function.Consumer<ServerPlayer> body) {
        if (c.player() instanceof ServerPlayer p) body.accept(p);
    }

    /** Отдельный класс: на выделенном сервере клиентские классы не загружаются. */
    private static final class Client {
        static void denied(Denied d) {
            works.repka.rysclaims.client.DenyFeedback.show(d.reason(), d.owner());
        }

        static void hello(Hello h) {
            works.repka.rysclaims.client.LandCache.hello(h);
        }

        static void region(Region r) {
            works.repka.rysclaims.client.LandCache.region(r);
        }

        static void diff(Diff d) {
            works.repka.rysclaims.client.LandCache.diff(d);
        }

        static void access(Access a) {
            works.repka.rysclaims.client.LandCache.access(a);
        }

        static void result(EditResult r) {
            works.repka.rysclaims.client.LandCache.result(r);
        }

        static void state(LandState s) {
            works.repka.rysclaims.client.LandCache.state(s);
        }

        static void alert(OwnerAlert a) {
            works.repka.rysclaims.client.DenyFeedback.ownerAlert(a.name(), a.reason(), a.plot());
        }

        static void log(LogPage p) {
            works.repka.rysclaims.client.LandCache.log(p);
        }

        static void question(Question q) {
            works.repka.rysclaims.client.DenyFeedback.question(q);
        }
    }

    /** Может ли клиент принять пакеты земли. */
    public static boolean accepts(ServerPlayer player) {
        return player.connection != null && player.connection.hasChannel(Denied.TYPE);
    }

    public static void denied(ServerPlayer player, String reason, String owner) {
        PacketDistributor.sendToPlayer(player, new Denied(reason, owner));
    }

    /** Шлёт игроку, если его клиент знает этот пакет. */
    public static void send(ServerPlayer player, CustomPacketPayload payload) {
        if (player.connection != null && player.connection.hasChannel(payload.type())) PacketDistributor.sendToPlayer(player, payload);
    }
}

package works.repka.rysclaims.compat.sable;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.jetbrains.annotations.Nullable;
import works.repka.rysclaims.RYSClaims;
import works.repka.rysclaims.data.Chunks;
import works.repka.rysclaims.data.Rights;
import works.repka.rysclaims.server.ClaimService;
import works.repka.rysclaims.server.ClaimsServer;
import works.repka.rysclaims.server.DenyLog;
import works.repka.rysclaims.server.Loader;
import works.repka.rysclaims.server.Protection;

/**
 * Разборка летающей постройки Sable и Create Aeronautics (Simulated 1.3.2, CHANGELOG 0.0.6, разбор скептика 18.11, риск 5):
 * блоки подуровня ставятся в мир {@code SimAssemblyHelper.disassembleSubLevel} без событий. Над землёй, закрытой постройке,
 * разборка отменяется целиком, постройка остаётся собранной.
 *
 * <p>Правило то же, что у сборки ({@link SableAssembly#filter}): чанк открыт, если он ничей или принадлежит дому постройки
 * (команде земли, где её собрали). Ещё он открыт игроку, который сам дёрнул рычаг сборщика и может строить в этом чанке:
 * так своя постройка, собранная на ничьей земле (дома нет), разбирается у себя на базе. Игрок известен только у сборщика
 * ({@code AssemblePacket} от игрока, CFR), у поворотного подшипника и склеивающего клея его нет.
 *
 * <p>Где отменяется (CFR Simulated 1.3.2): {@code PhysicsAssemblerBlockEntity.throwDisassemblyExceptions} (её зовут и
 * при нажатии, и перед постановкой в мир; наше исключение Create показывает у сборщика как обычную ошибку разборки),
 * {@code SwivelBearingBlockEntity.disassemble} в начале (подшипник остаётся собранным) и сама
 * {@code disassembleSubLevel} как последняя преграда для остальных путей (склеивающий клей).
 */
public final class SableDisassembly {
    private static final ThreadLocal<Player> ACTOR = new ThreadLocal<>();
    /** Сборщик → игрок, который начал разборку (постановка в мир идёт через несколько тиков, уже без пакета). */
    private static final Map<BlockEntity, UUID> STARTED_BY = new WeakHashMap<>();
    /** Подуровень, разборку которого только что пропустила своя проверка (сборщик, подшипник): последняя преграда его не судит. */
    private static final ThreadLocal<Object> PASSED = new ThreadLocal<>();

    private SableDisassembly() {}

    // ---- игрок пакета сборщика ----

    /** Начало обработки {@code AssemblePacket}: игрок из {@code ServerPacketContext.player()}. */
    public static void packetStart(@Nullable Object context) {
        if (context == null) return;
        try {
            Method m = context.getClass().getMethod("player");
            ACTOR.set(m.invoke(context) instanceof Player p ? p : null);
        } catch (ReflectiveOperationException | RuntimeException e) {
            ACTOR.remove();
        }
    }

    public static void packetEnd() {
        ACTOR.remove();
    }

    /** Игрок, чей пакет сейчас обрабатывается (сборка и начало разборки идут внутри пакета). */
    public static @Nullable Player actor() {
        return ACTOR.get();
    }

    // ---- проверки ----

    /** Сборщик: перед разборкой. Возвращает исключение Create для броска или null. */
    public static @Nullable Exception assembler(Object self, @Nullable Object subLevel) {
        if (!(self instanceof BlockEntity be) || !(be.getLevel() instanceof ServerLevel level) || subLevel == null) return null;
        Player actor = ACTOR.get();
        UUID by;
        synchronized (STARTED_BY) {
            if (actor != null) STARTED_BY.put(be, actor.getUUID());
            by = actor != null ? actor.getUUID() : STARTED_BY.get(be);
        }
        UUID owner = blocked(level, subLevel, worldBox(subLevel), by);
        if (owner == null) {
            PASSED.set(subLevel);
            return null;
        }
        Protection.tellNear(level, be.getBlockPos(), ClaimService.Reason.DISASSEMBLE, owner, actor);
        return exception(be);
    }

    /** Поворотный подшипник: true значит «нельзя», разборка отменяется, у подшипника строка причины. */
    public static boolean swivel(Object self) {
        if (!(self instanceof BlockEntity be) || be.isRemoved() || !(be.getLevel() instanceof ServerLevel level)) return false;
        try {
            Object id = be.getClass().getMethod("getSubLevelID").invoke(be);
            if (!(id instanceof UUID uuid)) return false;
            Object container = Class.forName("dev.ryanhcode.sable.api.sublevel.SubLevelContainer", false, be.getClass().getClassLoader())
                .getMethod("getContainer", Level.class).invoke(null, level);
            if (container == null) return false;
            Object subLevel = container.getClass().getMethod("getSubLevel", UUID.class).invoke(container, uuid);
            if (subLevel == null) return false;
            UUID owner = blocked(level, subLevel, worldBox(subLevel), null);
            if (owner == null) {
                PASSED.set(subLevel);
                return false;
            }
            Exception e = exception(be);
            Field f = field(be.getClass(), "lastException");
            if (e != null && f != null) f.set(be, e);
            Method send = method(be.getClass(), "sendData");
            if (send != null) send.invoke(be);
            Protection.tellNear(level, be.getBlockPos(), ClaimService.Reason.DISASSEMBLE, owner, null);
            return true;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            RYSClaims.LOG.debug("[rysclaims] Sable disassembly not checked: {}", e.toString());
            return false;
        }
    }

    /** Последняя преграда в {@code disassembleSubLevel(уровень, подуровень, якорь, цель, поворот, звук)}: true значит «нельзя». */
    public static boolean place(Level level, Object subLevel, BlockPos anchor, BlockPos goal, Rotation rotation) {
        if (!(level instanceof ServerLevel sl)) return false;
        Object passed = PASSED.get();
        PASSED.remove();
        if (passed == subLevel) return false;
        int[] box = plotBox(subLevel, anchor, goal, rotation);
        if (box == null) box = worldBox(subLevel);
        UUID owner = blocked(sl, subLevel, box, null);
        if (owner == null) return false;
        Protection.tellNear(level, goal, ClaimService.Reason.DISASSEMBLE, owner, null);
        RYSClaims.LOG.info("RYSClaims: Sable contraption disassembly by {} cancelled: foreign land underneath", goal.toShortString());
        return true;
    }

    /**
     * Команда первого чанка рамки {minX, minZ, maxX, maxZ} (мировые блоки), закрытого постройке, или null. Журнал хозяев
     * получает строку машины «sable:disassembly».
     */
    static @Nullable UUID blocked(ServerLevel level, Object subLevel, int @Nullable [] box, @Nullable UUID actor) {
        if (box == null || !ClaimsServer.running()) return null;
        ClaimService s = ClaimsServer.service();
        String dim = Loader.id(level.dimension());
        if (!s.data().anyIn(dim) || !s.settings().protection) return null;
        UUID id = SableLand.id(subLevel);
        UUID home = id == null ? null : s.data().home(id);
        for (int cx = box[0] >> 4; cx <= box[2] >> 4; cx++) {
            for (int cz = box[1] >> 4; cz <= box[3] >> 4; cz++) {
                long chunk = Chunks.pack(cx, cz);
                UUID owner = s.data().owner(dim, chunk);
                if (owner == null || owner.equals(home)) continue;
                if (actor != null && s.can(actor, dim, chunk, Rights.Action.BUILD)) continue;
                DenyLog log = ClaimsServer.log();
                if (log != null) log.add(owner, actor, "sable:disassembly", actor == null, ClaimService.Reason.MACHINE, dim, (cx << 4) + 8, level.getSeaLevel(), (cz << 4) + 8);
                return owner;
            }
        }
        return null;
    }

    /** Рамка подуровня в мире сейчас ({@code SubLevel.boundingBox()}, мировые координаты): {minX, minZ, maxX, maxZ}. */
    static int @Nullable [] worldBox(Object subLevel) {
        try {
            Object b = subLevel.getClass().getMethod("boundingBox").invoke(subLevel);
            return box(b);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    /**
     * Куда лягут блоки участка: углы рамки участка ({@code getPlot().getBoundingBox()}, координаты участка) переводятся
     * как у {@code AssemblyTransform}: цель + поворот(позиция − якорь).
     */
    static int @Nullable [] plotBox(Object subLevel, BlockPos anchor, BlockPos goal, Rotation rotation) {
        try {
            Object plot = subLevel.getClass().getMethod("getPlot").invoke(subLevel);
            int[] local = box(plot.getClass().getMethod("getBoundingBox").invoke(plot));
            if (local == null) return null;
            int minX = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
            for (int x : new int[] {local[0], local[2]}) {
                for (int z : new int[] {local[1], local[3]}) {
                    BlockPos w = goal.offset(new BlockPos(x - anchor.getX(), 0, z - anchor.getZ()).rotate(rotation));
                    minX = Math.min(minX, w.getX());
                    minZ = Math.min(minZ, w.getZ());
                    maxX = Math.max(maxX, w.getX());
                    maxZ = Math.max(maxZ, w.getZ());
                }
            }
            return new int[] {minX, minZ, maxX, maxZ};
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    /** Рамка Sable (целая или дробная, методы {@code minX()} …) в {minX, minZ, maxX, maxZ}. */
    private static int @Nullable [] box(@Nullable Object b) throws ReflectiveOperationException {
        if (b == null) return null;
        double minX = num(b, "minX"), minZ = num(b, "minZ"), maxX = num(b, "maxX"), maxZ = num(b, "maxZ");
        if (minX > maxX || minZ > maxZ) return null;
        return new int[] {(int) Math.floor(minX), (int) Math.floor(minZ), (int) Math.floor(maxX), (int) Math.floor(maxZ)};
    }

    private static double num(Object b, String name) throws ReflectiveOperationException {
        Method m = b.getClass().getMethod(name);
        m.setAccessible(true);
        return ((Number) m.invoke(b)).doubleValue();
    }

    /** {@code new AssemblyException(причина)} Create (тип берётся из поля {@code lastException} блок-сущности). */
    private static @Nullable Exception exception(BlockEntity be) {
        try {
            Field f = field(be.getClass(), "lastException");
            if (f == null) return null;
            Constructor<?> c = f.getType().getConstructor(Component.class);
            return (Exception) c.newInstance(Component.translatable(RYSClaims.MODID + ".disassemble.refused"));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            return null;
        }
    }

    private static @Nullable Field field(Class<?> type, String name) {
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException e) {
                // выше
            }
        }
        return null;
    }

    private static @Nullable Method method(Class<?> type, String name) {
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                Method m = c.getDeclaredMethod(name);
                m.setAccessible(true);
                return m;
            } catch (NoSuchMethodException e) {
                // выше
            }
        }
        return null;
    }

    /** Бросить проверяемое исключение из обработчика перехвата без {@code throws}. */
    @SuppressWarnings("unchecked")
    public static <T extends Throwable> void sneaky(Throwable t) throws T {
        throw (T) t;
    }
}

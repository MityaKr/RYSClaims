package works.repka.rysclaims.compat.create;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.jetbrains.annotations.Nullable;
import works.repka.rysclaims.RYSClaims;
import works.repka.rysclaims.server.ClaimService;
import works.repka.rysclaims.server.Machines;
import works.repka.rysclaims.server.Protection;

/**
 * Разборка постройки Create 6.0.10 над землёй, закрытой ей (разбор скептика 18.11, риск 6). Раньше блоки на закрытую землю
 * выпадали предметами (путь Create для твёрдости -1), а содержимое сундуков постройки терялось: кто занял чанк на пути
 * чужого лифта, обнулял его груз. Теперь разборка отменяется целиком, постройка остаётся собранной и стоит, у блока
 * управления в очках Create строка причины ({@code lastException}), игрокам рядом замок у прицела.
 *
 * <p>Где отменяется (проверено CFR): {@code disassemble()} блоков управления (подшипник и ветряк, часовой подшипник,
 * поршень, шкив, лифт) и {@code GantryContraptionEntity.disassemble()} (портал зовёт её сам из тика). Разборка при поломке
 * блока управления ({@code remove()}, блок-сущность уже {@code isRemoved}) не отменяется: иначе сущность постройки без
 * хозяина исчезла бы вместе с блоками; там остаётся прежнее правило (блоки на закрытую землю выпадают). Вагонетки и поезда
 * тоже по прежнему правилу.
 *
 * <p>Проверка: каждый блок постройки переводится в мир тем же {@code StructureTransform}, что и в {@code addBlocksToWorld},
 * чанки судятся как машина с известным местом ({@link Machines#disassembly}). Портал зовёт разборку каждый тик, пока
 * стоит, поэтому итог по сущности помнится 20 тиков.
 */
public final class CreateDisassembly {
    private static final String[] ENTITY_FIELDS = {"movedContraption", "hourHand", "minuteHand"};
    private static final int REMEMBER_TICKS = 20;
    private static final Map<Entity, Memo> MEMO = new WeakHashMap<>();

    private record Memo(long tick, @Nullable UUID owner) {}

    private CreateDisassembly() {}

    /** Блок управления собирается разобрать свои постройки: true значит «нельзя», разборка отменяется. */
    public static boolean refuseController(Object self) {
        if (!(self instanceof BlockEntity be) || be.isRemoved() || be.getLevel() == null || be.getLevel().isClientSide) return false;
        try {
            for (String name : ENTITY_FIELDS) {
                Field f = field(be.getClass(), name);
                if (f == null || !(f.get(be) instanceof Entity e) || !e.isAlive()) continue;
                UUID owner = blocked(e);
                if (owner == null) continue;
                explain(be, owner);
                Protection.tellNear(be.getLevel(), be.getBlockPos(), ClaimService.Reason.DISASSEMBLE, owner, null);
                return true;
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            RYSClaims.LOG.debug("[rysclaims] разборка Create не проверена: {}", e.toString());
        }
        return false;
    }

    /** Сущность постройки сама разбирается (портал): true значит «нельзя». */
    public static boolean refuseEntity(Object self) {
        if (!(self instanceof Entity e) || !e.isAlive() || e.level().isClientSide) return false;
        UUID owner = blocked(e);
        if (owner == null) return false;
        Protection.tellNear(e.level(), e.blockPosition(), ClaimService.Reason.DISASSEMBLE, owner, null);
        return true;
    }

    /** Команда первого закрытого чанка, куда разборка положила бы блок, или null. */
    static @Nullable UUID blocked(Entity entity) {
        long now = entity.level().getGameTime();
        synchronized (MEMO) {
            Memo m = MEMO.get(entity);
            if (m != null && now - m.tick < REMEMBER_TICKS && now >= m.tick) return m.owner;
        }
        UUID owner = null;
        try {
            Object contraption = entity.getClass().getMethod("getContraption").invoke(entity);
            Method make = method(entity.getClass(), "makeStructureTransform");
            if (contraption != null && make != null) {
                Object transform = make.invoke(entity);
                Method apply = transform.getClass().getMethod("apply", BlockPos.class);
                Map<?, ?> blocks = (Map<?, ?>) contraption.getClass().getMethod("getBlocks").invoke(contraption);
                List<BlockPos> targets = new ArrayList<>(blocks.size());
                for (Object local : blocks.keySet()) {
                    if (local instanceof BlockPos p) targets.add((BlockPos) apply.invoke(transform, p));
                }
                BlockPos from = contraption instanceof ContraptionPlace place ? place.rysclaims$place() : null;
                owner = Machines.disassembly(entity.level(), from, targets, "create:contraption"
                    + (from == null ? "" : " @" + from.getX() + " " + from.getY() + " " + from.getZ()));
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            RYSClaims.LOG.debug("[rysclaims] разборка Create не проверена: {}", e.toString());
        }
        synchronized (MEMO) {
            MEMO.put(entity, new Memo(now, owner));
        }
        return owner;
    }

    /** Строка в очках Create у блока управления: {@code lastException = new AssemblyException(причина)}, затем {@code sendData}. */
    private static void explain(BlockEntity be, UUID owner) {
        try {
            Field f = field(be.getClass(), "lastException");
            if (f == null) return;
            Constructor<?> c = f.getType().getConstructor(Component.class);
            f.set(be, c.newInstance(Component.translatable(RYSClaims.MODID + ".disassemble.refused")));
            Method send = method(be.getClass(), "sendData");
            if (send != null) send.invoke(be);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            // только пояснение
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
}

package works.repka.rysclaims.compat.jade;

import java.lang.reflect.Method;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.jetbrains.annotations.Nullable;
import works.repka.rysclaims.RYSClaims;
import works.repka.rysclaims.server.Protection;

/**
 * Jade 15.10 не показывает содержимое там, где игроку нельзя «Открывать» (разбор скептика 18.11, пункт 2). По CFR Jade
 * 15.10.6 содержимое собирает сервер: {@code ItemStorageProvider.putData(Accessor)} и {@code FluidStorageProvider.putData}
 * кладут предметы и жидкости в данные подсказки, а сам Jade прячет их только у сундука с ванильным замком
 * ({@code lockKey}). Перехват в начале {@code putData} не даёт собрать содержимое и кладёт свою метку с именем земли; на
 * клиенте перехват {@code ItemStorageProvider.append} пишет вместо предметов «содержимое скрыто». Метка Jade
 * {@code Locked} кладётся тоже: клиент без нашего перехвата покажет «заперто».
 *
 * <p>Типы Jade не подключаются при сборке: аксессор приходит {@code Object}, его методы зовутся отражением (интерфейс
 * {@code snownee.jade.api.Accessor}, загрузчик тот же, что у объекта).
 */
public final class JadeHide {
    /** Метка в данных подсказки: имя земли для игрока (пустое, если земля от него скрыта). */
    public static final String KEY = "rysclaims:hidden";

    private static volatile @Nullable Methods methods;

    private record Methods(Method level, Method player, Method data, Method target) {}

    private JadeHide() {}

    /** Сервер: скрыть ли содержимое для этого аксессора. Если да, метки уже лежат в данных подсказки. */
    public static boolean hide(Object accessor) {
        try {
            Methods m = methods(accessor);
            if (m == null) return false;
            if (!(m.level.invoke(accessor) instanceof Level level) || level.isClientSide) return false;
            if (!(m.player.invoke(accessor) instanceof Player player)) return false;
            Object target = m.target.invoke(accessor);
            String name;
            if (target instanceof BlockEntity be) name = Protection.hiddenFrom(player, level, be.getBlockPos(), null);
            else if (target instanceof Entity e) name = Protection.hiddenFrom(player, level, null, e);
            else return false;
            if (name == null) return false;
            if (m.data.invoke(accessor) instanceof CompoundTag tag) {
                tag.putString(KEY, name);
                tag.putBoolean("Locked", true);
            }
            return true;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            return false;
        }
    }

    /** Клиент: строка «содержимое скрыто» вместо предметов, если сервер положил метку. */
    public static @Nullable Component hiddenLine(Object accessor) {
        try {
            Methods m = methods(accessor);
            if (m == null || !(m.data.invoke(accessor) instanceof CompoundTag tag) || !tag.contains(KEY)) return null;
            String name = tag.getString(KEY);
            Component line = name.isEmpty() ? Component.translatable(RYSClaims.MODID + ".jade.hidden")
                : Component.translatable(RYSClaims.MODID + ".jade.hidden_named", name);
            return line.copy().withStyle(ChatFormatting.GRAY);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            return null;
        }
    }

    /** Клиент: добавить строку в подсказку ({@code ITooltip.add(Component)}). */
    public static boolean addLine(Object tooltip, Component line) {
        try {
            Class<?> type = Class.forName("snownee.jade.api.ITooltip", false, tooltip.getClass().getClassLoader());
            type.getMethod("add", Component.class).invoke(tooltip, line);
            return true;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            return false;
        }
    }

    private static @Nullable Methods methods(Object accessor) throws ReflectiveOperationException {
        Methods m = methods;
        if (m != null) return m;
        Class<?> type = Class.forName("snownee.jade.api.Accessor", false, accessor.getClass().getClassLoader());
        m = new Methods(type.getMethod("getLevel"), type.getMethod("getPlayer"), type.getMethod("getServerData"), type.getMethod("getTarget"));
        methods = m;
        return m;
    }
}

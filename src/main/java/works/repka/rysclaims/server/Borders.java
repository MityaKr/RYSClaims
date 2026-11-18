package works.repka.rysclaims.server;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;
import works.repka.rysclaims.compat.sable.SableLand;
import works.repka.rysclaims.data.Chunks;
import works.repka.rysclaims.data.Rights;

/**
 * Через границу земли без игрока (план, раздел 11, строки 7, 19–28): вода и лава, огонь, молния, воронки, раздатчики,
 * падающие блоки, скалк, плиты и растяжки, рост деревьев и лиан. Зовутся из перехватов
 * {@code mixin/} тысячи раз за тик, поэтому сначала сравниваются номера чанков двух позиций: внутри одного чанка ответ
 * сразу, поиск в таблице только на границе чанков и только если в измерении есть земля.
 */
public final class Borders {
    private Borders() {}

    private static @Nullable ClaimService service(@Nullable Object level) {
        if (!(level instanceof ServerLevel) || !ClaimsServer.running()) return null;
        return ClaimsServer.service();
    }

    /** Жидкость из {@code from} в {@code to}: на землю другой команды только с настройкой сервера. */
    public static boolean fluid(BlockGetter level, BlockPos from, BlockPos to) {
        if (((from.getX() ^ to.getX()) >> 4) == 0 && ((from.getZ() ^ to.getZ()) >> 4) == 0) return true;
        ClaimService s = service(level);
        if (s == null) return true;
        return cross(s, (Level) level, from, to, s.settings().fluidsCross);
    }

    /** Огонь из {@code from} в {@code to}: на землю другой команды только с настройкой сервера. */
    public static boolean fire(Level level, BlockPos from, BlockPos to) {
        if (((from.getX() ^ to.getX()) >> 4) == 0 && ((from.getZ() ^ to.getZ()) >> 4) == 0) return true;
        ClaimService s = service(level);
        if (s == null) return true;
        return cross(s, level, from, to, s.settings().fireCross);
    }

    /**
     * Воронка и выбрасыватель из {@code from} в {@code to}: на землю другой команды только с настройкой сервера; со свободной
     * земли можно ({@link ClaimService#mayInsert}).
     */
    public static boolean hopper(Level level, BlockPos from, BlockPos to) {
        if (((from.getX() ^ to.getX()) >> 4) == 0 && ((from.getZ() ^ to.getZ()) >> 4) == 0) return true;
        ClaimService s = service(level);
        if (s == null) return true;
        String dim = Loader.id(level.dimension());
        if (!s.data().anyIn(dim)) return true;
        BlockPos wf = SableLand.toWorld(level, from), wt = SableLand.toWorld(level, to);
        return s.mayInsert(dim, Chunks.pack(wf.getX() >> 4, wf.getZ() >> 4), Chunks.pack(wt.getX() >> 4, wt.getZ() >> 4), s.settings().hoppersCross);
    }

    /**
     * Раздатчик из {@code from} в {@code to}: на землю другой команды и со свободной земли на землю команды только с
     * настройкой воронок. Раздатчик льёт лаву, ставит блоки и огонь, поэтому со свободной земли закрыт, в отличие от воронки.
     */
    public static boolean dispenser(Level level, BlockPos from, BlockPos to) {
        if (((from.getX() ^ to.getX()) >> 4) == 0 && ((from.getZ() ^ to.getZ()) >> 4) == 0) return true;
        ClaimService s = service(level);
        if (s == null) return true;
        return cross(s, level, from, to, s.settings().hoppersCross);
    }

    /** Падающий блок, начавший падение в {@code start}, ложится в {@code to}: вместе с поршнями. */
    public static boolean falling(Level level, BlockPos start, BlockPos to) {
        if (((start.getX() ^ to.getX()) >> 4) == 0 && ((start.getZ() ^ to.getZ()) >> 4) == 0) return true;
        ClaimService s = service(level);
        if (s == null) return true;
        return cross(s, level, start, to, s.settings().pistonsCross);
    }

    /** Заряд скалка от катализатора в {@code origin} переходит в {@code to}: вместе с огнём. */
    public static boolean sculk(Object level, BlockPos origin, BlockPos to) {
        if (((origin.getX() ^ to.getX()) >> 4) == 0 && ((origin.getZ() ^ to.getZ()) >> 4) == 0) return true;
        ClaimService s = service(level);
        if (s == null) return true;
        return cross(s, (Level) level, origin, to, s.settings().fireCross);
    }

    /**
     * Вагонетка с воронкой (строка 27): команда земли, где её поставили (запоминается при первой работе в данных сущности);
     * на земле другой команды она не тянет, если сервер не разрешил воронкам ходить через границу.
     */
    public static boolean minecartHopper(net.minecraft.world.entity.Entity cart) {
        ClaimService s = service(cart.level());
        if (s == null || s.settings().hoppersCross || !s.settings().protection) return true;
        String dim = Loader.id(cart.level().dimension());
        net.minecraft.nbt.CompoundTag data = cart.getPersistentData();
        java.util.UUID here = s.data().owner(dim, Chunks.pack(cart.getBlockX() >> 4, cart.getBlockZ() >> 4));
        if (!data.contains(HOME)) {
            // старая метка строкой (до 0.0.39) переводится в UUID один раз
            if (data.contains(HOME_OLD)) {
                String old = data.getString(HOME_OLD);
                data.remove(HOME_OLD);
                try {
                    if (old.isEmpty()) data.putIntArray(HOME, new int[0]);
                    else data.putUUID(HOME, java.util.UUID.fromString(old));
                } catch (IllegalArgumentException bad) {
                    data.putIntArray(HOME, new int[0]);
                }
            } else {
                if (here == null) data.putIntArray(HOME, new int[0]);
                else data.putUUID(HOME, here);
                return true;
            }
        }
        if (here == null) return true;
        // перепроверка 18.11, пункт 15: сравнение UUID, без строки на каждом тике у каждой вагонетки
        return data.hasUUID(HOME) && here.equals(data.getUUID(HOME));
    }

    /** Команда земли, где вагонетка с воронкой впервые заработала: UUID, пустой массив значит ничья земля. */
    private static final String HOME = "rysclaims:home_id";
    private static final String HOME_OLD = "rysclaims:home";

    /**
     * Плита, растяжка, кнопка от стрелы на земле команды (строка 7): игрок и снаряд игрока жмут её, только если у игрока
     * есть «Двери, кнопки, рычаги». Мобы и снаряды без хозяина жмут, как в игре.
     */
    public static boolean triggers(Level level, BlockPos pos, net.minecraft.world.entity.Entity e) {
        net.minecraft.world.entity.player.Player player = null;
        if (e instanceof net.minecraft.world.entity.player.Player p) player = p;
        else if (e instanceof net.minecraft.world.entity.projectile.Projectile proj && proj.getOwner() instanceof net.minecraft.world.entity.player.Player p) player = p;
        return player == null || Protection.allowed(player, level, pos, Rights.Action.USE);
    }

    /** Убирает из списка тех, кто не может нажать плиту или растяжку в {@code pos}. Зовётся перехватами, только на сервере. */
    public static <T extends net.minecraft.world.entity.Entity> java.util.List<T> pressers(Level level, BlockPos pos, java.util.List<T> list) {
        if (list.isEmpty() || service(level) == null) return list;
        ClaimService s = ClaimsServer.service();
        String dim = Loader.id(level.dimension());
        if (!s.data().anyIn(dim) || s.data().owner(dim, Chunks.pack(pos.getX() >> 4, pos.getZ() >> 4)) == null) return list;
        java.util.List<T> out = null;
        for (int i = 0; i < list.size(); i++) {
            T e = list.get(i);
            boolean ok = triggers(level, pos, e);
            if (!ok && out == null) out = new java.util.ArrayList<>(list.subList(0, i));
            else if (ok && out != null) out.add(e);
        }
        return out == null ? list : out;
    }

    /**
     * Рост дерева, гриба, азалии (строка 22): кто посадил саженец, игра не хранит, поэтому правило по земле. Рост
     * отменяется, если в радиусе {@code radius} блоков есть земля другой команды, чем под саженцем.
     */
    public static boolean growth(Level level, BlockPos pos, int radius) {
        ClaimService s = service(level);
        if (s == null || !s.settings().protection) return true;
        String dim = Loader.id(level.dimension());
        if (!s.data().anyIn(dim)) return true;
        java.util.UUID home = s.data().owner(dim, Chunks.pack(pos.getX() >> 4, pos.getZ() >> 4));
        for (int cx = (pos.getX() - radius) >> 4; cx <= (pos.getX() + radius) >> 4; cx++) {
            for (int cz = (pos.getZ() - radius) >> 4; cz <= (pos.getZ() + radius) >> 4; cz++) {
                java.util.UUID o = s.data().owner(dim, Chunks.pack(cx, cz));
                if (o != null && !o.equals(home)) return false;
            }
        }
        return true;
    }

    private static boolean cross(ClaimService s, Level level, BlockPos from, BlockPos to, boolean allowed) {
        String dim = Loader.id(level.dimension());
        if (!s.data().anyIn(dim)) return true;
        BlockPos wf = SableLand.toWorld(level, from), wt = SableLand.toWorld(level, to);
        return s.mayCross(dim, Chunks.pack(wf.getX() >> 4, wf.getZ() >> 4), Chunks.pack(wt.getX() >> 4, wt.getZ() >> 4), allowed);
    }

    /**
     * Огонь от молнии: молния трезубца с «Громовержцем» судится правом «Строить» её игрока, любая молния ещё и как огонь
     * из точки удара.
     */
    public static boolean lightning(Level level, BlockPos strike, BlockPos fire, @Nullable net.minecraft.world.entity.player.Player cause) {
        if (cause != null && !Protection.allowed(cause, level, fire, Rights.Action.BUILD)) return false;
        return fire(level, strike, fire);
    }
}

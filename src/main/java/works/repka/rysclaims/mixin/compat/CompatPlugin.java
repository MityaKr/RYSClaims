package works.repka.rysclaims.mixin.compat;

import java.util.List;
import java.util.Set;
import net.neoforged.fml.loading.LoadingModList;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;
import works.repka.rysclaims.compat.ApplyWatch;
import works.repka.rysclaims.compat.Links;

/**
 * Перехваты чужих модов включаются, только если связка включена в {@code config/rysclaims-common.toml} ({@link Links}) и мод
 * загружен: имя класса перехвата начинается с ключа связки. Классы помечены {@code @Pseudo}, а набор
 * {@code rysclaims.compat.mixins.json} необязательный: без мода или с другой его версией RYSClaims работает, перехват просто
 * не встаёт (строка в логе Mixin и в строке {@code [rysclaims] связки:} при запуске сервера).
 *
 * <p>Выключатель стоит здесь, а не проверкой в обработчике: выключенный перехват не применяется вовсе, поэтому выключить можно
 * и тот, что после обновления мода ломает загрузку класса. {@link ApplyWatch} записывает, какие обработчики действительно
 * получили место вызова в цели (при {@code require = 0} несовпавший обработчик молча пропускается).
 */
public final class CompatPlugin implements IMixinConfigPlugin {
    @Override
    public void onLoad(String mixinPackage) {
        ApplyWatch.install();
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        String simple = simple(mixinClassName);
        Links.Link link = Links.Link.ofMixin(simple);
        if (link == null || !Links.on(link) || !loaded(link.modId)) return false;
        Links.target(simple, targetClassName);
        return true;
    }

    private static String simple(String className) {
        return className.substring(className.lastIndexOf('.') + 1);
    }

    private static boolean loaded(String modId) {
        LoadingModList list = LoadingModList.get();
        return list != null && list.getModFileById(modId) != null;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {}

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
        try {
            ApplyWatch.applied(simple(mixinClassName), targetClass.name, mixinInfo.getClassNode(0));
        } catch (RuntimeException e) {
            // только учёт
        }
    }
}

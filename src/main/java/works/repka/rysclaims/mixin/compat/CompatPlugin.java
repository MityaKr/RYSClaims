package works.repka.rysclaims.mixin.compat;

import java.util.List;
import java.util.Map;
import java.util.Set;
import net.neoforged.fml.loading.LoadingModList;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/**
 * Перехваты чужих модов включаются, только если мод загружен: имя класса перехвата начинается с ключа из {@link #MODS}.
 * Классы помечены {@code @Pseudo}, а набор {@code rysclaims.compat.mixins.json} необязательный: без мода или с другой
 * его версией RYSClaims работает, перехват просто не встаёт (строка в логе Mixin).
 */
public final class CompatPlugin implements IMixinConfigPlugin {
    private static final Map<String, String> MODS = Map.of(
        "Ae2", "ae2",
        "Oritech", "oritech",
        "Cyclic", "cyclic",
        "Ie", "immersiveengineering",
        "Rftools", "rftoolsbuilder",
        "Enderio", "enderio",
        "Occultism", "occultism",
        "Draconic", "draconicevolution",
        "Sable", "sable");

    @Override
    public void onLoad(String mixinPackage) {}

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        String simple = mixinClassName.substring(mixinClassName.lastIndexOf('.') + 1);
        for (Map.Entry<String, String> e : MODS.entrySet()) {
            if (simple.startsWith(e.getKey())) return loaded(e.getValue());
        }
        return false;
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
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}
}

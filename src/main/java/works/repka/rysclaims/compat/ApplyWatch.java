package works.repka.rysclaims.compat;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.spongepowered.asm.mixin.MixinEnvironment;
import org.spongepowered.asm.mixin.transformer.IMixinTransformer;
import org.spongepowered.asm.mixin.transformer.ext.Extensions;
import org.spongepowered.asm.mixin.transformer.ext.IExtension;
import org.spongepowered.asm.mixin.transformer.ext.ITargetClassContext;

/**
 * Учёт перехватов связок: какие обработчики после применения получили место вызова в цели. Расширение Mixin, а не
 * {@code IMixinConfigPlugin.postApply}: обработчики {@code @WrapOperation} MixinExtras внедряются поздно, в своём расширении
 * после всех перехватов класса, и в {@code postApply} плагина их ещё нет. Это расширение добавляется позже MixinExtras и
 * видит класс уже целиком.
 *
 * <p>Не в пакете миксинов: Mixin не даёт грузить оттуда обычные классы.
 */
public final class ApplyWatch implements IExtension {
    /** Цель (внутреннее имя) → перехваты связок, применённые к ней (пишет {@code CompatPlugin.postApply}). */
    private static final Map<String, Set<String>> PENDING = new ConcurrentHashMap<>();
    private static volatile @Nullable Extensions registry;
    private volatile boolean retired;

    private ApplyWatch() {}

    /** Ставит учёт; если API Mixin другой, учёт молча не ставится (связки тогда видны как «цель не найдена»). */
    public static void install() {
        try {
            MixinEnvironment env = MixinEnvironment.getCurrentEnvironment();
            if (env.getActiveTransformer() instanceof IMixinTransformer mt && mt.getExtensions() instanceof Extensions ex) {
                registry = ex;
                ex.add(new ApplyWatch());
                // плагин грузится после выбора активных расширений: пересобрать список, иначе учёт не зовётся
                ex.select(env);
            }
        } catch (RuntimeException | LinkageError e) {
            org.slf4j.LoggerFactory.getLogger("rysclaims").warn("[rysclaims] link accounting not installed: {}", e.toString());
        }
    }

    /** Перехват {@code mixin} применён к цели: обработчики посчитаются, когда класс будет готов целиком. */
    public static void applied(String mixin, String targetInternal, ClassNode mixinNode) {
        Links.handlers(mixin, handlers(mixinNode));
        PENDING.computeIfAbsent(targetInternal, k -> ConcurrentHashMap.newKeySet()).add(mixin);
    }

    @Override
    public boolean checkActive(MixinEnvironment environment) {
        return !retired;
    }

    @Override
    public void preApply(ITargetClassContext context) {}

    @Override
    public void postApply(ITargetClassContext context) {
        if (retired) return;
        try {
            keepLast();
            ClassNode target = context.getClassNode();
            Set<String> mixins = PENDING.remove(target.name);
            if (mixins == null) return;
            for (String m : mixins) Links.injected(m, called(target, Links.handlersOf(m)));
        } catch (RuntimeException e) {
            // только учёт: он не должен ронять загрузку класса
        }
    }

    /**
     * MixinExtras ставит своё расширение поздних внедрений ({@code @WrapOperation}) позже нас: тогда учёт встаёт в конец
     * заново (новым экземпляром, этот уходит), чтобы видеть класс после поздних внедрений.
     */
    private void keepLast() {
        Extensions ex = registry;
        if (ex == null) return;
        List<IExtension> all = ex.getExtensions();
        if (all.isEmpty() || all.get(all.size() - 1) == this) return;
        retired = true;
        ex.add(new ApplyWatch());
        ex.select(MixinEnvironment.getCurrentEnvironment());
    }

    @Override
    public void export(MixinEnvironment env, String name, boolean force, ClassNode classNode) {}

    /** Методы перехвата с аннотацией внедрения (Mixin {@code @Inject}, {@code @ModifyVariable}..., MixinExtras). */
    static Set<String> handlers(ClassNode mixin) {
        Set<String> out = new HashSet<>();
        for (MethodNode m : mixin.methods) {
            if (injector(m.visibleAnnotations) || injector(m.invisibleAnnotations)) out.add(m.name);
        }
        return out;
    }

    private static boolean injector(List<AnnotationNode> list) {
        if (list == null) return false;
        for (AnnotationNode a : list) {
            if (a.desc.startsWith("Lorg/spongepowered/asm/mixin/injection/") || a.desc.startsWith("Lcom/llamalad7/mixinextras/injector/")) {
                return true;
            }
        }
        return false;
    }

    /**
     * Обработчики, у которых в цели есть место вызова. Слитый в цель обработчик переименован ({@code handler$zza000$имя},
     * {@code wrapOperation$...$имя}), поэтому сравнение по концу имени.
     */
    static Set<String> called(ClassNode target, Set<String> handlers) {
        Set<String> out = new HashSet<>();
        if (handlers.isEmpty()) return out;
        for (MethodNode m : target.methods) {
            for (AbstractInsnNode insn : m.instructions) {
                if (!(insn instanceof MethodInsnNode call) || !call.owner.equals(target.name)) continue;
                for (String h : handlers) {
                    if (call.name.equals(h) || call.name.endsWith("$" + h)) out.add(h);
                }
            }
        }
        return out;
    }
}

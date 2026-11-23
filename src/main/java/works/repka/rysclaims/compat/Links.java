package works.repka.rysclaims.compat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Связки RYSClaims с чужими модами: выключатели в {@code config/rysclaims-common.toml}, раздел {@code [links]}, и что из
 * них на самом деле встало.
 *
 * <p>Выключатель читается до загрузки игры (его спрашивает {@code CompatPlugin.shouldApplyMixin}), поэтому файл разбирается
 * здесь построчно, без системы настроек NeoForge: она в это время ещё не работает. Выключенная связка не применяется вовсе
 * (кода перехвата в игре нет), так что админ может выключить и перехват, который после обновления мода роняет загрузку.
 * Цена: правка вступает в силу после перезапуска. Связки без миксина (Botania, отражение Sable) спрашивают {@link #on} в
 * начале своего кода.
 *
 * <p>Класс не трогает классы игры: его грузит плагин миксинов до неё.
 */
public final class Links {
    private static final Logger LOG = LoggerFactory.getLogger("rysclaims");
    public static final String FILE = "rysclaims-common.toml";
    public static final String SECTION = "links";

    /** Связка: ключ в настройках, имя в логе, modid, начало имени классов перехватов (null: перехватов нет). */
    public enum Link {
        CREATE("create", "Create", "create", "Create"),
        ORITECH("oritech", "Oritech", "oritech", "Oritech"),
        AE2("ae2", "AE2", "ae2", "Ae2"),
        IE("immersiveengineering", "IE", "immersiveengineering", "Ie"),
        CYCLIC("cyclic", "Cyclic", "cyclic", "Cyclic"),
        RFTOOLS("rftools", "RFTools", "rftoolsbuilder", "Rftools"),
        ENDERIO("enderio", "EnderIO", "enderio", "Enderio"),
        OCCULTISM("occultism", "Occultism", "occultism", "Occultism"),
        DRACONIC("draconic", "Draconic", "draconicevolution", "Draconic"),
        FOREGOING("industrialforegoing", "IF", "industrialforegoing", "Foregoing"),
        PRODUCTIVE_BEES("productivebees", "Productive Bees", "productivebees", "ProductiveBees"),
        BOTANIA("botania", "Botania", "botania", null),
        SABLE("sable", "Sable", "sable", "Sable"),
        JADE("jade", "Jade", "jade", "Jade"),
        GRAVESTONE("gravestone", "Gravestone", "gravestone", null);

        public final String key, title, modId;
        public final @Nullable String prefix;

        Link(String key, String title, String modId, @Nullable String prefix) {
            this.key = key;
            this.title = title;
            this.modId = modId;
            this.prefix = prefix;
        }

        /** Связка перехвата по простому имени его класса, или null. */
        public static @Nullable Link ofMixin(String simpleName) {
            for (Link l : values()) if (l.prefix != null && simpleName.startsWith(l.prefix)) return l;
            return null;
        }
    }

    private static volatile @Nullable Map<String, Boolean> switches;
    /** Перехват (простое имя) → цели, на которые он заявлен (записывает shouldApplyMixin для включённых связок). */
    private static final Map<String, Set<String>> TARGETS = new ConcurrentHashMap<>();
    /** Перехват → имена его обработчиков. */
    private static final Map<String, Set<String>> HANDLERS = new ConcurrentHashMap<>();
    /** Перехват → обработчики, которые вызываются хотя бы в одной цели (записывает postApply). */
    private static final Map<String, Set<String>> INJECTED = new ConcurrentHashMap<>();

    private Links() {}

    /** Включена ли связка (по умолчанию да; файла нет или ключа нет: да). */
    public static boolean on(Link link) {
        Map<String, Boolean> s = switches;
        if (s == null) s = switches = read();
        return s.getOrDefault(link.key, Boolean.TRUE);
    }

    private static Map<String, Boolean> read() {
        try {
            Path file = net.neoforged.fml.loading.FMLPaths.CONFIGDIR.get().resolve(FILE);
            if (!Files.isRegularFile(file)) return Map.of();
            return parse(Files.readAllLines(file, StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException | LinkageError e) {
            LOG.warn("[rysclaims] could not read {}: all links enabled ({})", FILE, e.toString());
            return Map.of();
        }
    }

    /** Значения раздела {@code [links]} файла TOML: {@code ключ = true|false}, комментарии и прочие разделы мимо. */
    public static Map<String, Boolean> parse(List<String> lines) {
        Map<String, Boolean> out = new LinkedHashMap<>();
        boolean inside = false;
        for (String raw : lines) {
            String line = raw.strip();
            int hash = line.indexOf('#');
            if (hash >= 0) line = line.substring(0, hash).strip();
            if (line.isEmpty()) continue;
            if (line.startsWith("[")) {
                inside = line.equals("[" + SECTION + "]");
                continue;
            }
            if (!inside) continue;
            int eq = line.indexOf('=');
            if (eq <= 0) continue;
            String key = unquote(line.substring(0, eq).strip());
            String value = line.substring(eq + 1).strip().toLowerCase(Locale.ROOT);
            if (value.equals("true")) out.put(key, true);
            else if (value.equals("false")) out.put(key, false);
        }
        return out;
    }

    private static String unquote(String s) {
        return s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"") ? s.substring(1, s.length() - 1) : s;
    }

    /** Для проверок: подставить выключатели вместо файла. */
    public static void setForTest(@Nullable Map<String, Boolean> values) {
        switches = values;
    }

    // ---- что встало: пишет плагин миксинов ----

    public static void target(String mixin, String target) {
        TARGETS.computeIfAbsent(mixin, k -> ConcurrentHashMap.newKeySet()).add(target);
    }

    public static void handlers(String mixin, Set<String> names) {
        HANDLERS.computeIfAbsent(mixin, k -> ConcurrentHashMap.newKeySet()).addAll(names);
    }

    public static Set<String> handlersOf(String mixin) {
        return HANDLERS.getOrDefault(mixin, Set.of());
    }

    public static void injected(String mixin, Set<String> names) {
        INJECTED.computeIfAbsent(mixin, k -> ConcurrentHashMap.newKeySet()).addAll(names);
    }

    /** Обработчики без места вызова: перехват → имена (для подробного лога). */
    public static Map<String, Set<String>> missing() {
        Map<String, Set<String>> out = new java.util.TreeMap<>();
        for (String m : TARGETS.keySet()) {
            Set<String> left = new java.util.TreeSet<>(HANDLERS.getOrDefault(m, Set.of()));
            left.removeAll(INJECTED.getOrDefault(m, Set.of()));
            if (!HANDLERS.containsKey(m)) left.add("(hook not applied)");
            if (!left.isEmpty()) out.put(m, left);
        }
        return out;
    }

    /** Состояние одной связки после {@link #check}. */
    public record State(Link link, Kind kind, String note) {}

    public enum Kind { UP, DOWN, OFF }

    /**
     * Проверяет все связки: грузит (без инициализации) каждую цель перехватов, чтобы Mixin применил их сейчас, а не когда
     * машина впервые заработает, затем считает обработчики с местом вызова в цели.
     *
     * @param loaded загружен ли мод (modid)
     * @param loader загрузчик классов модов
     * @param extra проверка связки без миксина (Botania) или дополнительная (Sable): null значит «в порядке», иначе причина
     */
    public static List<State> check(Predicate<String> loaded, ClassLoader loader, Map<Link, java.util.function.Supplier<String>> extra) {
        List<State> out = new ArrayList<>();
        for (Link link : Link.values()) {
            if (!on(link)) {
                out.add(new State(link, Kind.OFF, ""));
                continue;
            }
            if (!loaded.test(link.modId)) {
                out.add(new State(link, Kind.DOWN, "mod not found"));
                continue;
            }
            List<String> notes = new ArrayList<>();
            boolean down = false;
            if (link.prefix != null) {
                int total = 0, done = 0;
                Set<String> missing = new LinkedHashSet<>();
                for (Map.Entry<String, Set<String>> e : TARGETS.entrySet()) {
                    if (Link.ofMixin(e.getKey()) != link) continue;
                    for (String t : e.getValue()) {
                        try {
                            Class.forName(t, false, loader);
                        } catch (ClassNotFoundException | LinkageError ex) {
                            missing.add(t.substring(t.lastIndexOf('.') + 1));
                        }
                    }
                }
                for (Map.Entry<String, Set<String>> e : TARGETS.entrySet()) {
                    if (Link.ofMixin(e.getKey()) != link) continue;
                    Set<String> all = HANDLERS.getOrDefault(e.getKey(), Set.of());
                    Set<String> hit = INJECTED.getOrDefault(e.getKey(), Set.of());
                    total += Math.max(all.size(), 1);
                    for (String h : all) if (hit.contains(h)) done++;
                }
                if (total == 0 || done == 0) {
                    down = true;
                    notes.add("target not found");
                } else if (done < total) {
                    notes.add("partly " + done + " of " + total);
                }
                if (!missing.isEmpty()) notes.add("no classes " + String.join(", ", missing));
            }
            java.util.function.Supplier<String> x = extra.get(link);
            if (x != null) {
                String why = x.get();
                if (why != null) {
                    notes.add(why);
                    if (link.prefix == null) down = true;
                }
            }
            out.add(new State(link, down ? Kind.DOWN : Kind.UP, String.join("; ", notes)));
        }
        return out;
    }

    /** Строка лога: {@code [rysclaims] связки: встали A, B; не встали C (причина); выключены D}. */
    public static String line(List<State> states) {
        Map<Kind, List<String>> by = new EnumMap<>(Kind.class);
        for (Kind k : Kind.values()) by.put(k, new ArrayList<>());
        for (State s : states) by.get(s.kind()).add(s.note().isEmpty() ? s.link().title : s.link().title + " (" + s.note() + ")");
        return "[rysclaims] links: applied " + list(by.get(Kind.UP)) + "; not applied " + list(by.get(Kind.DOWN)) + "; disabled "
            + list(by.get(Kind.OFF));
    }

    private static String list(List<String> l) {
        return l.isEmpty() ? "none" : String.join(", ", l);
    }
}

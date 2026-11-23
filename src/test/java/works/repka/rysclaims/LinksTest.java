package works.repka.rysclaims;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import works.repka.rysclaims.compat.Links;

/** Выключатели связок: разбор файла, связка по имени перехвата, строка лога. */
class LinksTest {
    @AfterEach
    void reset() {
        Links.setForTest(null);
    }

    @Test
    void parsesOnlyLinksSection() {
        Map<String, Boolean> m = Links.parse(List.of(
            "[other]", "create = false",
            "#comment", "[links]", "\t# Create (mod id create)", "\tcreate = false", "\toritech = true # tail", "\"ae2\" = FALSE",
            "broken", "sable = maybe"));
        assertEquals(Map.of("create", false, "oritech", true, "ae2", false), m);
    }

    @Test
    void mixinPrefix() {
        assertEquals(Links.Link.CREATE, Links.Link.ofMixin("CreateArmMixin"));
        assertEquals(Links.Link.FOREGOING, Links.Link.ofMixin("ForegoingAreaMixin"));
        assertEquals(Links.Link.PRODUCTIVE_BEES, Links.Link.ofMixin("ProductiveBeesFarmerMixin"));
        assertEquals(Links.Link.IE, Links.Link.ofMixin("IeExcavatorMixin"));
        assertNull(Links.Link.ofMixin("CompatPlugin"));
    }

    @Test
    void defaultsOnAndSwitchOff() {
        Links.setForTest(Map.of("create", false));
        assertFalse(Links.on(Links.Link.CREATE));
        assertTrue(Links.on(Links.Link.BOTANIA));
    }

    @Test
    void reportLine() {
        Links.setForTest(Map.of("cyclic", false));
        // мод oritech «загружен», но перехваты не записаны: цель не найдена; Botania проверка в порядке; прочих модов нет
        List<Links.State> s = Links.check(id -> id.equals("oritech") || id.equals("botania"), getClass().getClassLoader(),
            Map.of(Links.Link.BOTANIA, () -> null));
        String line = Links.line(s);
        assertTrue(line.startsWith("[rysclaims] links: applied Botania; not applied "), line);
        assertTrue(line.contains("Oritech (target not found)"), line);
        assertTrue(line.contains("Create (mod not found)"), line);
        assertTrue(line.endsWith("; disabled Cyclic"), line);
    }
}

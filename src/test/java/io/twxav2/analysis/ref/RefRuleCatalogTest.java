package io.twxav2.analysis.ref;

import io.twxav2.analysis.ref.rules.Rule;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Rule IDs use the format R-<CAT>-<nnn>, ordered by category in reports and unique. */
class RefRuleCatalogTest {

    @Test
    void catalogSize() {
        assertEquals(156, RefRuleCatalog.SIZE);
        assertEquals(RefRuleCatalog.SIZE, RefRuleCatalog.all().size());
    }

    @Test
    void idsFormatUniquePerCategoryAscending() {
        Set<String> seen = new HashSet<>();
        Map<String, Integer> lastByCat = new HashMap<>();
        for (int i = 0; i < RefRuleCatalog.SIZE; i++) {
            Rule r = RefRuleCatalog.byIndex(i);
            assertNotNull(r);
            var m = java.util.regex.Pattern.compile("R-([A-Z]+)-(\\d{3})").matcher(r.id);
            assertTrue(m.matches(), "Regel-ID-Format R-<KAT>-<nnn> erwartet: " + r.id);
            assertTrue(Set.of("APP", "DEP", "SVC", "BPD", "JS", "UI", "BO", "QA")
                    .contains(m.group(1)), "unbekannte Kategorie: " + r.id);
            assertTrue(seen.add(r.id), "doppelte Regel-ID " + r.id);
            int n = Integer.parseInt(m.group(2));
            Integer last = lastByCat.put(m.group(1), n);
            assertTrue(last == null || n == last + 1,
                    "je Kategorie aufsteigend erwartet nach " + m.group(1) + "-" + last + ": " + r.id);
            assertFalse(r.title.isBlank());
            assertFalse(r.category.isBlank());
            assertTrue(r.impact >= 1 && r.impact <= 3);
        }
        assertEquals("R-APP-001", RefRuleCatalog.byIndex(0).id);
        assertEquals("R-QA-009", RefRuleCatalog.byIndex(155).id);
    }

    @Test
    void reportOrderTheRuleGroups() {
        List<String> cats = RefRuleCatalog.all().stream().map(r -> r.category).distinct().toList();
        // AppRules -> DependencyRules -> ServiceRules -> BpdRules -> ScriptRules ...
        assertEquals("Application", cats.get(0));
        int app = cats.indexOf("Application"), dep = cats.indexOf("Dependencies");
        int svc = cats.indexOf("Service"), bpd = cats.indexOf("Process");
        int js = cats.indexOf("Script");
        assertTrue(app < dep && dep < svc && svc < bpd && bpd < js,
                "Gruppenreihenfolge geaendert: " + cats);
    }
}

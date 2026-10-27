package io.twxav2.analysis.ref;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * German catalog texts: completeness of the maintained keys R-APP-001-R-QA-009
 * (title/description in German, English via the *En field) and
 * fallback to the English original text.
 */
class RuleTextsDeTest {

    @Test
    void catalogGaplessMaintained() {
        for (int i = 0; i < RefRuleCatalog.SIZE; i++) {
            String id = RefRuleCatalog.byIndex(i).id;
            for (String field : new String[]{"title", "category", "rationale", "guidance"}) {
                assertNotNull(RuleTextsDe.get(id, field), id + "." + field + " fehlt");
                assertFalse(RuleTextsDe.get(id, field).isBlank(), id + "." + field + " ist leer");
            }
        }
    }

    @Test
    void textsFromTheProperties() {
        // get trims; the texts come from the properties (UTF-8).
        assertTrue(RuleTextsDe.get("R-DEP-006", "title").startsWith("Referenz auf ein fehlendes"));
        assertEquals("Abhängigkeiten", RuleTextsDe.get("R-DEP-006", "category"));
    }

    @Test
    void missingKeyNotPresent() {
        assertNull(RuleTextsDe.get("R-TST-999", "title"));
        assertNull(RuleTextsDe.get("R-DEP-006", "unbekanntesFeld"));
    }

    @Test
    void orFallsOnEnglishBack() {
        assertTrue(RuleTextsDe.or("R-DEP-006", "title", "Missing reference").startsWith("Referenz auf ein fehlendes"));
        assertEquals("Missing reference", RuleTextsDe.or("R-TST-999", "title", "Missing reference"));
    }
}

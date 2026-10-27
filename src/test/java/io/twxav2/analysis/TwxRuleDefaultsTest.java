package io.twxav2.analysis;

import io.twxav2.analysis.TwxRule.Hit;
import io.twxav2.ingest.TwxParseResult;
import io.twxav2.model.Finding.Severity;
import io.twxav2.testsupport.TwxObjects;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Catalog defaults of the TwxRule SPI plus the Hit convenience constructors. */
class TwxRuleDefaultsTest {

    /** Rule providing only the required methods — everything else falls back to the defaults. */
    private static final TwxRule NUR_PFLICHT = new TwxRule() {
        @Override
        public String id() {
            return "R-TST-900";
        }

        @Override
        public String title() {
            return "Minimalregel";
        }

        @Override
        public List<Hit> analyze(TwxParseResult result) {
            return List.of(new Hit(Severity.INFO, "1.a", "A", "hit"));
        }
    };

    @Test
    void catalogDefaults() {
        assertEquals("Allgemein", NUR_PFLICHT.category());
        assertEquals(Severity.MINOR, NUR_PFLICHT.defaultSeverity());
        assertEquals(1, NUR_PFLICHT.impact());
        assertEquals(Integer.MAX_VALUE, NUR_PFLICHT.cap());
        assertTrue(NUR_PFLICHT.defaultEnabled());
        assertEquals("", NUR_PFLICHT.rationale());
        assertEquals("", NUR_PFLICHT.guidance());
        assertNull(NUR_PFLICHT.titleEn());
        assertNull(NUR_PFLICHT.categoryEn());
        assertNull(NUR_PFLICHT.rationaleEn());
        assertNull(NUR_PFLICHT.guidanceEn());
    }

    @Test
    void contextAnalysisDelegatesAtParsed() {
        var ctx = new RuleContext(TwxObjects.result(), java.util.Map.of());
        List<Hit> hits = NUR_PFLICHT.analyze(ctx);
        assertEquals(1, hits.size());
        assertEquals("hit", hits.get(0).message());
    }

    @Test
    void hitConstructorsFillsFieldsNot() {
        var einfach = new Hit(Severity.MAJOR, "1.x", "X", "msg");
        assertNull(einfach.location());
        assertNull(einfach.line());
        assertNull(einfach.snippet());
        assertFalse(einfach.needsReview());
        assertNull(einfach.messageEn());

        var zweisprachig = new Hit(Severity.MAJOR, "1.x", "X", "msg", "en message");
        assertEquals("en message", zweisprachig.messageEn());
        assertNull(zweisprachig.location());

        var umfangreich = new Hit(Severity.MAJOR, "1.x", "X", "msg", "Skript", 7, "var x;", true);
        assertEquals("Skript", umfangreich.location());
        assertEquals(7, umfangreich.line());
        assertEquals("var x;", umfangreich.snippet());
        assertTrue(umfangreich.needsReview());
        assertNull(umfangreich.messageEn());
    }
}

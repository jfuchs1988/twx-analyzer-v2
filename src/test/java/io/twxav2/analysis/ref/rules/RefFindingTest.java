package io.twxav2.analysis.ref.rules;

import io.twxav2.analysis.ref.model.TwxObject;
import io.twxav2.analysis.ref.model.TwxPackage;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Finding record: of() enrichment, position, path text, JSON round trip, key. */
class RefFindingTest {

    private static final Rule REGEL = new Rule("X01", "Titel", "Kategorie", Severity.MAJOR,
            "Beschreibung", "Behebung", "Doku") {
        @Override
        public void check(RuleContext ctx, List<Finding> out) {
            // not needed for this test
        }
    };

    private static TwxObject sampleObject() {
        var p = new TwxPackage();
        p.name = "Demo App";
        p.acronym = "DEMO";
        p.snapshotId = "snap-1";
        return new TwxObject("1.abc", "Svc", "process", "snap-1", "<process/>", p);
    }

    @Test
    void ofEnrichesObjectAndLineAt() {
        Finding f = Finding.of(REGEL, sampleObject(), "step-1", "Step", "loc", "Nachricht",
                "line 3: var x = 1;");             // format of rule evidence (ScriptRules.line)
        assertEquals("X01", f.ruleId);
        assertEquals("MAJOR", f.severity);
        assertEquals(40, f.score);                              // MAJOR weight x default impact 1
        assertEquals(3, f.line);
        assertEquals("var x = 1;", f.snippet);
        assertEquals("Demo App", f.packageName);
        assertEquals("Service", f.objectTypeLabel);
        assertFalse(f.toolkit);
    }

    @Test
    void ofWithoutObjectAndWithBrokenLineReference() {
        Finding f = Finding.of(REGEL, null, null, null, null, "Nachricht", "line abc:x");
        assertEquals("", f.packageName);
        assertEquals("", f.objectId);
        assertEquals(0, f.line);                                // number parse error ignored
        assertEquals("", f.itemId);
    }

    @Test
    void atSetsPositionOnlyWhenEvidenceEmpty() {
        Finding f = Finding.of(REGEL, sampleObject(), "i", "n", "l", "m", "");
        f.at(7, 2, "quellzeile");
        assertEquals(7, f.line);
        assertEquals(2, f.column);
        assertEquals("line 7:2: quellzeile", f.evidence);
        f.at(9, 0, null);                                       // evidence already set -> stays
        assertEquals("line 7:2: quellzeile", f.evidence);
        assertEquals("", f.snippet);
    }

    @Test
    void pathTextWithAndWithoutDetails() {
        Finding f = Finding.of(REGEL, sampleObject(), "step", "Zahlungs Schritt", "loc", "m", "");
        assertEquals("Demo App (DEMO) / Service / Svc / Zahlungs Schritt", f.path());
        Finding g = Finding.of(REGEL, null, null, null, "Koastelle", "m", "");
        assertEquals(" () / Koastelle", g.path());
    }

    @Test
    void jsonRoundTrip() {
        Finding f = Finding.of(REGEL, sampleObject(), "i1", "n1", "l1", "msg", "ev");
        f.toolkit = true;
        f.tags.add("auto");
        f.at(4, 1, "sn");
        Map<String, Object> json = f.toJson();
        Finding g = Finding.fromJson(json);
        assertEquals(f.key(), g.key());
        assertEquals(f.ruleId, g.ruleId);
        assertEquals(f.packageName, g.packageName);
        assertEquals(f.message, g.message);
        assertEquals(f.score, g.score);
        assertEquals(f.line, g.line);
        assertEquals(f.column, g.column);
        assertEquals(f.snippet, g.snippet);
        assertTrue(g.toolkit);
        assertEquals(List.of("auto"), g.tags);
        assertEquals("high", g.confidence);
        // missing numeric fields -> 0, null values -> empty
        Finding leer = Finding.fromJson(Map.of());
        assertEquals(0, leer.score);
        assertEquals("", leer.ruleId);
    }

    @Test
    void keyStable() {
        Finding f = Finding.of(REGEL, sampleObject(), "i1", "n1", "l1", "m", "");
        assertEquals("X01|process|Svc|n1|l1", f.key());
    }
}

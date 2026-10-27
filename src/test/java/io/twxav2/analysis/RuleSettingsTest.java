package io.twxav2.analysis;

import io.twxav2.analysis.RuleSettings.Override;
import io.twxav2.model.Finding.Severity;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Merge semantics of the settings: this overrides base field by field, exclusion
 *  lists as duplicate-free union (base first, case-insensitive). */
class RuleSettingsTest {

    @Test
    void overrideIsEmptyOnlyWithoutAllFields() {
        Override o = new Override();
        assertTrue(o.isEmpty());
        o.enabled = false;
        assertFalse(o.isEmpty());
    }

    @Test
    void mergeOverridesOnlySetFields() {
        RuleSettings base = new RuleSettings();
        Override b = new Override();
        b.enabled = true;
        b.severity = Severity.MAJOR;
        b.impact = 2;
        b.cap = 10;
        base.putOverride("R-TST-001", b);
        base.thresholds.put("complexity", 30);
        base.thresholds.put("logStatements", 5);

        RuleSettings snap = new RuleSettings();
        Override s = new Override();
        s.severity = Severity.CRITICAL;          // only severity overridden
        snap.putOverride("R-TST-001", s);
        snap.thresholds.put("complexity", 99);

        RuleSettings out = snap.mergedWith(base);
        Override m = out.override("R-TST-001");
        assertEquals(Severity.CRITICAL, m.severity);   // aus snap
        assertEquals(Boolean.TRUE, m.enabled);         // aus base behalten
        assertEquals(Integer.valueOf(2), m.impact);
        assertEquals(Integer.valueOf(10), m.cap);
        assertEquals(99, out.thresholds.get("complexity"));
        assertEquals(5, out.thresholds.get("logStatements"));
    }

    @Test
    void mergeWithoutBaseAndNewRule() {
        RuleSettings snap = new RuleSettings();
        Override s = new Override();
        s.cap = 3;
        snap.putOverride("R-TST-007", s);
        RuleSettings out = snap.mergedWith(null);
        assertEquals(Integer.valueOf(3), out.override("R-TST-007").cap);
        assertNull(out.override("R-TST-001"));
    }

    @Test
    void unionWithoutDuplicatesCaseInsensitive() {
        List<String> out = RuleSettings.union(
                java.util.Arrays.asList(" Alpha ", "beta"),
                java.util.Arrays.asList("ALPHA", "", null, "gamma"));
        assertEquals(List.of("Alpha", "beta", "gamma"), out);
        assertTrue(RuleSettings.union(null, null).isEmpty());
        assertTrue(RuleSettings.union(List.of("x"), null).equals(List.of("x")));
    }
}

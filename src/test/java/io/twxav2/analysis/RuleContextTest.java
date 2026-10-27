package io.twxav2.analysis;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RuleContextTest {

    @Test
    void thresholdsWithMatchingType() {
        var ctx = new RuleContext(null, Map.of("n", 7, "l", 9L, "b", true, "s", "x"));
        assertEquals(7, ctx.th("n", 1));
        assertEquals(9L, ctx.th("l", 1L));
        assertTrue(ctx.th("b", false));
        assertEquals("x", ctx.th("s", "d"));
    }

    @Test
    void missingOrWrongTypeReturnsDefault() {
        var ctx = new RuleContext(null, Map.of("n", 7, "s", 5));
        assertEquals(1, ctx.th("missing", 1)); // key missing -> default
        assertEquals(7L, ctx.th("n", 1L));     // Number hits the long overload
        assertEquals("d", ctx.th("s", "d"));   // number instead of string -> default
        assertFalse(ctx.th("n", false));       // number instead of boolean -> default
    }

    @Test
    void scratchCarriesRunDerivations() {
        var shared = new java.util.HashMap<String, Object>();
        shared.put("k", 1);
        assertSame(shared, new RuleContext(null, Map.of(), shared).scratch());
        var fresh = new RuleContext(null, Map.of());       // 2-Arg -> eigener Scratch
        assertTrue(fresh.scratch().isEmpty());
        fresh.scratch().put("ref", "x");
        assertSame("x", fresh.scratch().get("ref"));
    }
}

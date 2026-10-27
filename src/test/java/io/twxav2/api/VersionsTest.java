package io.twxav2.api;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Version comparison for toolkit names: digit sections numeric, letters
 * literally (case-insensitive), suffixes: digit continuation = upgrade, anything
 * else = incomparable variant (rank 0).
 */
class VersionsTest {

    @Test
    void nullIsAlwaysOlder() {
        assertEquals(0, Versions.compare(null, null));
        assertEquals(-1, Versions.compare(null, "1.0"));
        assertEquals(1, Versions.compare("1.0", null));
        assertEquals(0, Versions.compare("1.0", "1.0"));
    }

    @Test
    void digitsSectionsNumeric() {
        assertTrue(Versions.compare("1.10.2", "1.9.3") > 0);      // 10 > 9, not "9" > "10"
        assertTrue(Versions.compare("2.0", "10.0") < 0);          // BigInteger, no overflow
        assertTrue(Versions.compare("1.0.1", "1.0") > 0);         // digit continuation = newer
        assertTrue(Versions.compare("1.0", "1.0.1") < 0);
    }

    @Test
    void characterSectionsLiteralCaseSensitive() {
        assertEquals(0, Versions.compare("1.0a", "1.0A"));
        assertTrue(Versions.compare("1.0b", "1.0a") > 0);
        // digit section ranks before letter section
        assertTrue(Versions.compare("1.1", "1.a") < 0);
        assertTrue(Versions.compare("1.a", "1.1") > 0);
    }

    @Test
    void variantsSuffixesAreIncomparable() {
        assertEquals(0, Versions.compare("8.6.0.0_TC", "8.6.0.0"));
        assertEquals(0, Versions.compare("8.6.0.0", "8.6.0.0_TC"));
        assertEquals(0, Versions.compare("1.0-SNAPSHOT", "1.0"));
    }

    @Test
    void groupsKeyAbbreviationBeforeName() {
        assertEquals("demo", Versions.key("DEMO", "Lang"));
        assertEquals("lang", Versions.key(" ", "Lang"));
        assertEquals("lang", Versions.key(null, "Lang"));
        assertNull(Versions.key(null, null));
        assertNull(Versions.key("", "  "));
    }
}

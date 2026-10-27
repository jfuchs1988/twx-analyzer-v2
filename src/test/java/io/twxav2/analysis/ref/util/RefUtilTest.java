package io.twxav2.analysis.ref.util;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Helpers for the regex-based TWX parsers: Xml extraction with
 * entity resolution, JSON round trip, time budget for regular expressions.
 */
class RefUtilTest {

    // ---------- Xml ----------

    @Test
    void xmlTextAndEntities() {
        String x = "<a><name>Gr&#252;sse &amp; Co. &lt;alt&gt;</name><leer/><roh>x &amp; y</roh></a>";
        assertEquals("Grüsse & Co. <alt>", Xml.text(x, "name"));
        assertEquals("", Xml.text(x, "leer"));       // self-closing tag without content
        assertEquals("", Xml.text(x, "unbekannt"));
        assertEquals("x &amp; y", Xml.textRaw(x, "roh"));
        assertEquals("a&amp;b", Xml.escape("a&b"));
    }

    @Test
    void xmlCollectsTextsAndBlocks() {
        String x = "<r><epvId>1</epvId><epvId>2</epvId></r>";
        assertEquals(List.of("1", "2"), Xml.texts(x, "epvId"));
        String b = "<items><item><x>1</x></item><item><x>2</x></item></items>";
        assertEquals(2, Xml.blocks(b, "item").size());
        assertEquals(2, Xml.nestedBlocks(b, "item").size());
    }

    @Test
    void xmlAttributeAndBooleanValues() {
        String x = "<processParameter name=\"meinArg\" typ=\"1\"/>";
        assertEquals("meinArg", Xml.attr(x, "processParameter", "name"));
        assertEquals("1", Xml.attrOf(Xml.openTag(x, "processParameter", 0), "typ"));
        assertEquals("", Xml.attrOf("<item/>", "name"));
        assertTrue(Xml.bool("<a><f>true</f></a>", "f"));
        assertFalse(Xml.bool("<a><f>1</f></a>", "f"));
        assertEquals(7, Xml.integer("<a><n>7</n><m>kA</m></a>", "n", 0));
        assertEquals(5, Xml.integer("<a><m>kA</m></a>", "m", 5));
    }

    @Test
    void openTagFindsOnlyExactNames() {
        String x = "<itemX a=\"1\"><item a=\"2\"/></itemX>";
        assertEquals("<item a=\"2\"/>", Xml.openTag(x, "item", 0));
        assertEquals("", Xml.openTag("<a/>", "item", 0));
    }

    @Test
    void elementsOpenTagAndBody() {
        String x = "<l><lane n=\"a\">INhalt</lane><lane n=\"b\">zwei</lane></l>";
        List<String[]> els = Xml.elements(x, "lane");
        assertEquals(2, els.size());
        assertEquals("INhalt", els.get(0)[1]);
        assertTrue(els.get(1)[0].contains("n=\"b\""));
    }

    @Test
    void entityResolutionRobust() {
        assertEquals("&<>\"'", Xml.unescape("&amp;&lt;&gt;&quot;&apos;"));
        assertEquals("uä", Xml.unescape("u&#228;"));
        assertEquals("uä", Xml.unescape("u&#xE4;"));
        assertEquals("&unbekannt; x", Xml.unescape("&unbekannt; x"));
        assertEquals("ohne", Xml.unescape("ohne"));
    }

    // ---------- Json ----------

    @Test
    @SuppressWarnings("unchecked")
    void jsonRoundTrip() {
        Map<String, Object> m = Json.obj("s", "a\"b\nc", "n", 42, "d", 1.5, "b", true,
                "liste", List.of(1, 2), "kind", Json.obj("x", null));
        Object back = Json.parse(Json.write(m));
        assertTrue(back instanceof Map);
        Map<String, Object> r = (Map<String, Object>) back;
        assertEquals("a\"b\nc", r.get("s"));
        assertEquals(42, r.get("n"));
        assertEquals(1.5, (Double) r.get("d"), 1e-9);
        assertEquals(Boolean.TRUE, r.get("b"));
        assertEquals(List.of(1, 2), r.get("liste"));
        assertNull(((Map<String, Object>) r.get("kind")).get("x"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void jsonWriteVariantsAndNumbers() {
        assertEquals("{}", Json.write(Json.obj()));
        assertEquals("[]", Json.write(List.of()));
        assertEquals("[1,2]", Json.write(new Object[]{1, 2}));
        assertTrue(Json.writePretty(Json.obj("a", 1)).contains("\n  "));
        assertEquals(9223372036854775807L, ((Number) Json.parse("9223372036854775807")).longValue());
        assertEquals(3, Json.parse("3"));
        assertEquals("ä", ((Map<String, Object>) Json.parse("{\"k\":\"\\u00e4\"}")).get("k"));
        assertThrows(IllegalArgumentException.class, () -> Json.parse("{\"a\":1} x"));
        assertThrows(IllegalArgumentException.class, () -> Json.parse("[1,"));
    }

    // ---------- TimedText ----------

    @Test
    void timeBudgetStopsRegularExpression() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 5000; i++) sb.append('a');
        TimedText t = new TimedText(sb, -1, "suche"); // budget exceeded at deadline -> immediate
        assertEquals(sb.length(), t.length());
        assertEquals("a", t.subSequence(0, 1));
        assertEquals(sb.toString(), t.toString());
        assertThrows(TimedText.Timeout.class, () -> {
            for (int i = 0; i < 8192; i++) t.charAt(0);
        });
    }

    @Test
    void overDividesDeadline() {
        TimedText a = new TimedText("urtext", 60_000, "suche");
        TimedText b = a.over("anderer text");
        assertEquals("anderer text", b.toString());
        assertEquals('a', b.charAt(0));
    }
}

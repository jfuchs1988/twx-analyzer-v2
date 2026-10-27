package io.twxav2.analysis.script;

import io.twxav2.analysis.script.ScriptScanner.Fragment;
import io.twxav2.analysis.script.ScriptScanner.Literal;
import io.twxav2.testsupport.TwxObjects;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Edge cases of ScriptScanner: model-JSON path (broken, non-JS format,
 * empty contents, whitespace canonization) plus literal masking in
 * strip() (templates, regex classes, division vs. regex) and snippet truncation.
 */
class ScriptScannerEdgeTest {

    // ---------- Fragments / model JSON ----------

    @Test
    void notScriptObjectReturnsEarlyEmpty() {
        assertTrue(ScriptScanner.fragments(
                TwxObjects.obj("2051", "rest", "REST", "<script>var a;</script>")).isEmpty());
    }

    @Test
    void brokenModelJsonStaysSilent() {
        var o = TwxObjects.obj("25", "m1", "BPD", "<b><jsonData>{not-json</jsonData></b>");
        assertTrue(ScriptScanner.fragments(o).isEmpty());
    }

    @Test
    void notJavascriptFormatWithoutFragment() {
        var o = TwxObjects.obj("1", "m2", "P", "<b><jsonData>"
                + "{\"elements\":[{\"name\":\"T1\",\"script\":{\"content\":[\"x=1;\"]},\"scriptFormat\":\"jython\"},"
                + "{\"name\":\"T2\",\"script\":{\"content\":[\"y=2;\"]},\"scriptFormat\":\"JavaScript\"},"
                + "{\"name\":\"T3\",\"script\":{\"content\":[]}},"
                + "{\"name\":\"T4\",\"script\":{\"content\":5}}]}"
                + "</jsonData></b>");
        List<Fragment> fs = ScriptScanner.fragments(o);
        assertEquals(1, fs.size(), fs.toString());
        assertTrue(fs.get(0).js().contains("y=2;"));
    }

    @Test
    void duplicateDetectionIgnoresTabsAndNewlines() {
        var o = TwxObjects.obj("25", "m3", "BPD",
                "<b><script>var\t a;</script><jsonData>"
                        + "{\"elements\":[{\"name\":\"T\",\"script\":{\"content\":[\"var a;\\n\"]}}]}"
                        + "</jsonData></b>");
        List<Fragment> fs = ScriptScanner.fragments(o);
        assertEquals(1, fs.size(), fs.toString());
        assertEquals("Script block 1", fs.get(0).description());
    }

    // ---------- strip(): literal masking ----------

    @Test
    void stringEscapesSkipsTheFollowChar() {
        String js = "var s = \"m\\\"n\"; var t = 'k\\'j';";
        String masked = ScriptScanner.strip(js);
        // content blacked out, quote characters stay, escape is skipped
        assertTrue(masked.startsWith("var s = \""), masked);
        assertFalse(masked.contains("m"), "String-Inhalt muss weg sein: " + masked);
        assertFalse(masked.contains("k"), masked);
        assertTrue(masked.endsWith("\"; var t = '    ';") || masked.endsWith("';"), masked);
        assertTrue(masked.contains(";"), masked);
    }

    @Test
    void templateLiteralsWithEscapesAndRaw() {
        String js = "var t = `tpl ${'x'} ende`; var u = `a\\`b`; var v = `offen";
        String masked = ScriptScanner.strip(js);
        assertFalse(masked.contains("tpl"), masked);
        assertFalse(masked.contains("ende"), masked);
        assertFalse(masked.contains("${"), "Template-Ausdruck wird mitgeschwaerzt");
        assertFalse(masked.contains("offen"), "offenes Template bis zum Ende schwaerzen");
        assertTrue(masked.startsWith("var t = `"), "Struktur bleibt erhalten");
        // escaped backtick inside stays part of the literal: the 'b' before the
        // closing backtick is masked too, then a regular '; follows
        assertEquals(js.length(), masked.length(), "schwaerzt, nicht entfernt");
    }

    @Test
    void regexWithBracketAndEscapeIsRedacted() {
        String js = "var m = \"x\".replace(/a[\\/]b\\\\/g, '-');";
        String masked = ScriptScanner.strip(js);
        assertTrue(masked.startsWith("var m = \""), masked);
        assertFalse(masked.contains("[/"), "Regex-Klasse muss geschwaerzt sein: " + masked);
        assertFalse(masked.contains("b\\"), "Regex-Escape muss geschwaerzt sein: " + masked);
        assertTrue(masked.contains(".replace("), "Code um das Literal bleibt: " + masked);
        assertTrue(masked.endsWith("');"), "Rest der Zeile bleibt stehen: " + masked);
    }

    @Test
    void divisionAndAbortedRegexStayCode() {
        String div = "var q = 1 / 2;\nvar w = /unterm\n+ 3;";
        String masked = ScriptScanner.strip(div);
        assertTrue(masked.contains("1 / 2"), "Division nach Identifier: " + masked);
        assertTrue(masked.contains("/unterm"), "abgebrochenes Regex bleibt Code: " + masked);
    }

    @Test
    void lineRunsStaysReceived() {
        String js = "kommentar\nvar t = `zwei\nzeilen`;\nende";
        String masked = ScriptScanner.strip(js);
        assertEquals(js.chars().filter(c -> c == '\n').count(),
                masked.chars().filter(c -> c == '\n').count(), "Newlines bleiben fuer Zeilenzehlung stehen");
        assertEquals(1, ScriptScanner.lineAt(js, 0));
        assertEquals(4, ScriptScanner.lineAt(js, js.length()));
        // lines: 1 "a", 2 "b" (lone CR), 3 "c"; CRLF does not count twice
        assertEquals(1, ScriptScanner.lineAt("a\rb\r\nc", 0));
        assertEquals(2, ScriptScanner.lineAt("a\rb\r\nc", 4));   // offset on the LF of line 2
        assertEquals(3, ScriptScanner.lineAt("a\rb\r\nc", 7));   // past the end
    }

    // ---------- Snippet truncation ----------

    @Test
    void longSnippetIsAsWindowAroundOffset() {
        String lang = "x".repeat(200) + "ZIEL" + "y".repeat(100);
        String snip = ScriptScanner.snippetAt(lang, 250);
        assertTrue(snip.contains("ZIEL"), snip);
        assertTrue(snip.startsWith("…"), "Fenster beginnt vor dem Offset mit Ellipse");
        assertTrue(snip.length() <= 181, String.valueOf(snip.length()));
        // short snippet stays untouched
        assertEquals("kurz", ScriptScanner.snippetAt("kurz", 2));
        assertEquals("zweite", ScriptScanner.snippetAt("erste\nzweite\r\ndritte", 8));
        // lineText: line start suffices as offset
        assertEquals("zweite", ScriptScanner.lineText("erste\nzweite\ndritte", 2));
        assertEquals("", ScriptScanner.lineText("eine", 9));
    }

    @Test
    void stringLiteralsFindenPositionen() {
        List<Literal> ls = ScriptScanner.stringLiterals("var a = 'x';\nvar b = \"y\";");
        assertEquals(2, ls.size(), ls.toString());
        assertEquals("'x'", ls.get(0).text());
        assertEquals(8, ls.get(0).start());
        assertEquals("\"y\"", ls.get(1).text());
        // template literal is captured as well
        assertEquals(1, ScriptScanner.stringLiterals("var t = `tpl`;").size());
    }
}

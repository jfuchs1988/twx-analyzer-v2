package io.twxav2.analysis.script;

import io.twxav2.analysis.script.ScriptScanner.Fragment;
import io.twxav2.analysis.script.ScriptScanner.Literal;
import io.twxav2.ingest.TwxParseResult.ParsedObject;
import io.twxav2.testsupport.TwxObjects;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Plain unit tests for the ScriptScanner (no infrastructure). */
class ScriptScannerTest {

    // ---------- mayContainScripts ----------

    @Test
    void scriptPrefixesAndJsFiles() {
        assertTrue(ScriptScanner.mayContainScripts(TwxObjects.obj("1", "a", "P", "<x/>")));
        assertTrue(ScriptScanner.mayContainScripts(TwxObjects.obj("25", "b", "BPD", "<x/>")));
        assertTrue(ScriptScanner.mayContainScripts(TwxObjects.obj("64", "c", "CV", "<x/>")));
        assertTrue(ScriptScanner.mayContainScripts(TwxObjects.obj("61", "d", "lib.js", "var a;")));
        assertFalse(ScriptScanner.mayContainScripts(TwxObjects.obj("61", "e", "lib.txt", "x")));
        assertFalse(ScriptScanner.mayContainScripts(TwxObjects.obj("2051", "f", "REST", "<x/>")));
        assertFalse(ScriptScanner.mayContainScripts(TwxObjects.obj("1", "g", "P", null)));
    }

    // ---------- fragments ----------

    @Test
    void scriptBlockWithNamesLabel() {
        var o = TwxObjects.scriptObj("o1", "tw.local.x = 1;");
        List<Fragment> fs = ScriptScanner.fragments(o);
        assertEquals(1, fs.size());
        assertEquals("Script block 1 (main)", fs.get(0).description());
        assertEquals("tw.local.x = 1;", fs.get(0).js());
        assertEquals(1, fs.get(0).lineOffset());
    }

    @Test
    void selfClosingScriptSwallowsNothing() {
        var o = TwxObjects.obj("1", "o2", "P",
                "<x><script isNull=\"true\" /><script>var ok;</script></x>");
        List<Fragment> fs = ScriptScanner.fragments(o);
        assertEquals(1, fs.size());
        assertEquals("var ok;", fs.get(0).js());
    }

    @Test
    void scriptIdTagIsNotAsBlockRead() {
        var o = TwxObjects.obj("1", "o3", "P",
                "<x><scriptId>abc</scriptId><script>var ok;</script></x>");
        List<Fragment> fs = ScriptScanner.fragments(o);
        assertEquals(1, fs.size());
        assertEquals("var ok;", fs.get(0).js());
    }

    @Test
    void jsFileIsOneFragment() {
        var o = TwxObjects.obj("61", "o4", "lib.js", "function f() { return 1; }");
        List<Fragment> fs = ScriptScanner.fragments(o);
        assertEquals(1, fs.size());
        assertEquals("File", fs.get(0).description());
        assertEquals(o.content(), fs.get(0).js());
    }

    @Test
    void contentNullYieldsNoFragments() {
        assertTrue(ScriptScanner.fragments(TwxObjects.obj("1", "o5", "P", null)).isEmpty());
    }

    @Test
    void entitiesInScriptAreUnpacked() {
        var o = TwxObjects.obj("1", "o6", "P", "<x><script>a &amp;&amp; b &lt; c</script></x>");
        assertEquals("a && b < c", ScriptScanner.fragments(o).get(0).js());
    }

    @Test
    void bpmnJsonScriptFieldIsFragment() {
        var o = TwxObjects.obj("25", "o7", "BPD", "<b><jsonData>"
                + "{\"elements\":[{\"name\":\"T1\",\"script\":{\"content\":[\"tw.local.y=2;\"]}}]}"
                + "</jsonData></b>");
        List<Fragment> fs = ScriptScanner.fragments(o);
        assertEquals(1, fs.size());
        assertEquals("BPMN-JSON Script (T1)", fs.get(0).description());
        assertEquals("tw.local.y=2;", fs.get(0).js());
    }

    @Test
    void bpmnJsonDuplicateOfDomBlockDropped() {
        var o = TwxObjects.obj("25", "o8", "BPD",
                "<b><script>tw.local.y=2;</script><jsonData>"
                        + "{\"elements\":[{\"name\":\"T1\",\"script\":{\"content\":[\"tw.local.y = 2;\"]}}]}"
                        + "</jsonData></b>");
        List<Fragment> fs = ScriptScanner.fragments(o);
        assertEquals(1, fs.size()); // whitespace difference is fine (canon)
        assertEquals("Script block 1", fs.get(0).description());
    }

    @Test
    void notJsFormatRemovedAlsoTheDomBlock() {
        var o = TwxObjects.obj("25", "o9", "BPD",
                "<b><script>DATA=BLOCK</script><jsonData>"
                        + "{\"elements\":[{\"name\":\"T1\",\"scriptFormat\":\"text/plain\","
                        + "\"script\":{\"content\":[\"DATA=BLOCK\"]}}]}"
                        + "</jsonData></b>");
        assertTrue(ScriptScanner.fragments(o).isEmpty());
    }

    // ---------- strip ----------

    @Test
    void stripRedactsCommentsAndStringsEqualizesEqual() {
        String js = "var a = 1; // hide-me\nvar b = 'sec'; /* blk\nmulti */ var c;";
        String out = ScriptScanner.strip(js);
        assertEquals(js.length(), out.length());          // 1:1 positions
        assertEquals(js.chars().filter(c -> c == '\n').count(),
                out.chars().filter(c -> c == '\n').count());
        assertFalse(out.contains("hide-me"));
        assertFalse(out.contains("sec"));
        assertFalse(out.contains("blk"));
        assertTrue(out.contains("var a = 1;"));           // code stays
        assertTrue(out.contains("var c;"));
    }

    @Test
    void stripDifferentiatesRegexFromDivision() {
        assertEquals("var q = a / b;", ScriptScanner.strip("var q = a / b;"));
        String out = ScriptScanner.strip("var r = /a\\/b/g;");
        // regex literal blacked out, rest unchanged (positions 1:1)
        assertEquals("var r = " + " ".repeat(6) + "g;", out);
    }

    @Test
    void stripOpenStringLiteralUntilLineEnd() {
        String out = ScriptScanner.strip("var s = 'abc\ntail();");
        assertFalse(out.contains("abc"));
        assertTrue(out.contains("tail();"));              // next line stays code
    }

    // ---------- stringLiterals ----------

    @Test
    void stringLiteralsWithOffsets() {
        List<Literal> lits = ScriptScanner.stringLiterals("f('x', \"y\", `z`)");
        assertEquals(3, lits.size());
        assertEquals("'x'", lits.get(0).text());
        assertEquals(2, lits.get(0).start());
        assertEquals("\"y\"", lits.get(1).text());
        assertEquals("`z`", lits.get(2).text());
    }

    @Test
    void stringLiteralsSkipsComments() {
        assertTrue(ScriptScanner.stringLiterals("// 'im comment'\nvar a;").isEmpty());
        assertTrue(ScriptScanner.stringLiterals("/* \"auch hier\" */ var a;").isEmpty());
    }

    // ---------- lineAt / snippetAt / lineText ----------

    @Test
    void lineAtCountCRLFAndLFCRSeparated() {
        String js = "a\nb\r\nc\rd";
        assertEquals(1, ScriptScanner.lineAt(js, 0));
        assertEquals(2, ScriptScanner.lineAt(js, js.indexOf('b')));
        assertEquals(3, ScriptScanner.lineAt(js, js.indexOf('c')));
        assertEquals(4, ScriptScanner.lineAt(js, js.indexOf('d')));
    }

    @Test
    void snippetAtReturnsLineExcerpt() {
        String js = "line1\n   target 42   \nline3";
        assertEquals("target 42", ScriptScanner.snippetAt(js, js.indexOf("target")));
    }

    @Test
    void lineTextAfterLineNumber() {
        String js = "erste\r\nzweite\r\ndritte";
        assertEquals("zweite", ScriptScanner.lineText(js, 2));
        assertEquals("", ScriptScanner.lineText(js, 99));
    }

    // ---------- unescape / clean / maskScripts ----------

    @Test
    void unescapeAllEntitiesAndErrorFree() {
        assertEquals("<>&\"'AB",
                ScriptScanner.unescape("&lt;&gt;&amp;&quot;&apos;&#65;&#x42;"));
        assertEquals("&nope; bleibt", ScriptScanner.unescape("&nope; bleibt"));
        assertEquals("&#xZZ;", ScriptScanner.unescape("&#xZZ;")); // invalid number stays text
        String plain = "ohne entities";
        assertSame(plain, ScriptScanner.unescape(plain)); // guard: returns unchanged
    }

    @Test
    void cleanRemovesCdata() {
        assertEquals("var a;", ScriptScanner.clean("  <![CDATA[ var a; ]]>  "));
    }

    @Test
    void maskScriptsKeepsXmlScaffold() {
        String masked = ScriptScanner.maskScripts("<x><script>secret()</script><y/></x>");
        assertFalse(masked.contains("secret"));
        assertTrue(masked.contains("<x>"));
        assertTrue(masked.contains("<y/>"));
    }

    // ---------- Fragment-Cache ----------

    @Test
    void fragmentsAreCached() {
        ParsedObject o = TwxObjects.scriptObj("cache", "var c;");
        assertSame(ScriptScanner.fragments(o), ScriptScanner.fragments(o));
    }
}

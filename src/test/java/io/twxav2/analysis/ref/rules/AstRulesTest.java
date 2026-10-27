package io.twxav2.analysis.ref.rules;

import io.twxav2.analysis.ref.model.TwxModel;
import io.twxav2.analysis.ref.model.TwxObject;
import io.twxav2.analysis.ref.model.TwxPackage;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * AST rules (Rhino) with synthetic server/coach-view scripts: undeclared
 * names, tw.local declarations, zero propagation, null accesses, eval on
 * process data, infinite loops, debugger/parseInt, hardcoded codes, SQL concats.
 */
class AstRulesTest {

    static final String A = "11111111-1111-4111-8111-111111111111";

    static Rule rule(String id) {
        for (Rule r : AstRules.rules()) if (r.id.equals(id)) return r;
        throw new IllegalStateException("Regel nicht gefunden: " + id);
    }

    /** Embed script code XML-safely into a <script> block. */
    static String esc(String code) {
        return code.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /** App package with a script service; optional JS server file (assetTypeCode J). */
    static RuleContext ctx(String scriptBody, String serverFile) {
        TwxPackage app = new TwxPackage();
        app.id = "app"; app.name = "Demo"; app.acronym = "DEMO"; app.snapshotId = "app@snap";
        TwxModel m = new TwxModel();
        m.app = app;
        String vars = "<processVariable name=\"ok\"><processVariableId>V1</processVariableId></processVariable>";
        TwxObject svc = new TwxObject("1." + A, "Svc", "process", "v",
                "<process><processType>12</processType>" + vars
                        + "<item><processItemId>S</processItemId><name>Rechne</name>"
                        + "<tWComponentName>Script</tWComponentName><TWComponent>"
                        + "<script>" + esc(scriptBody) + "</script></TWComponent></item></process>", app);
        app.objects.put(svc.id, svc);
        if (serverFile != null) {
            TwxObject js = new TwxObject("61.srv", "helper.js", "managedAsset", "v",
                    "<twObject><assetTypeCode>J</assetTypeCode></twObject>", app);
            app.objects.put(js.id, js);
            app.jsFiles.put(js.id, serverFile);
        }
        return new RuleContext(m, Map.of(), false);
    }

    static List<Finding> run(String ruleId, RuleContext c) {
        List<Finding> out = new ArrayList<>();
        rule(ruleId).check(c, out);
        return out;
    }

    static List<Finding> run(String ruleId, String scriptBody) {
        return run(ruleId, ctx(scriptBody, null));
    }

    static String messages(List<Finding> f) {
        return String.join(" | ", f.stream().map(x -> x.message).toList());
    }

    // ---------- R-JS-025: undefined identifiers ----------

    @Test
    void undeclaredNameIsReported() {
        List<Finding> f = run("R-JS-025", "var a = 1;\nvar b = a + ghost;");
        assertEquals(1, f.size(), messages(f));
        assertTrue(f.get(0).message.contains("'ghost' is used but never declared"));
        assertEquals(2, f.get(0).line);   // line in the script code, not in the wrapper
    }

    @Test
    void allKnownWithoutFinding() {
        String code = """
                function helper() { var inner = 1; return inner; }
                var v = helper();
                var p = tw.local.ok, t = new TWDate(), n = Math.max(1, 2);
                try { helper(); } catch (e) { log.error(e); }
                if (typeof spaeteresVar !== 'undefined') { spaeteresVar = 1; }
                for (k in p) { }
                var s = JSON.stringify(v);
                java.lang.String.valueOf(1);
                return [v, s];""";
        List<Finding> f = run("R-JS-025", code);
        assertTrue(f.isEmpty(), messages(f));
    }

    @Test
    void namesFromTheServerFileAreGlobal() {
        String skript = "srvHelper(); var x = srvVar; implicitGlobal = 1;";
        assertTrue(run("R-JS-025", ctx(skript,
                "function srvHelper() { return 1; }\nvar srvVar = 2;\nimplicitGlobal = { a: 1 };")).isEmpty());
        // without the server file (and with another assetTypeCode) the names would be undefined
        assertFalse(run("R-JS-025", skript).isEmpty());
    }

    @Test
    void syntaxErrorFreeServerFileFallsOnRegexBack() {
        String skript = "broken()";
        assertTrue(run("R-JS-025", ctx(skript, "function broken( { ;"))
                .isEmpty());   // AST throws -> regex fallback finds function broken
    }

    // ---------- R-JS-026: tw.local without declaration ----------

    @Test
    void twLocalWithoutDeclaration() {
        List<Finding> f = run("R-JS-026",
                "tw.local.ok = 1;\ntw.local.unbekannt = 2;\ntw.local[\"fehlend\"] = 3;"
                        + "tw.system.coachVariables[\"cv\"] = 4;");
        assertEquals(2, f.size(), messages(f));
        assertTrue(messages(f).contains("tw.local.unbekannt"));
        assertTrue(messages(f).contains("tw.local.fehlend"));
    }

    @Test
    void serviceWithoutScriptsReturnsNothing() {
        TwxPackage app = new TwxPackage();
        app.id = "app"; app.name = "Demo"; app.acronym = "DEMO";
        TwxModel m = new TwxModel();
        m.app = app;
        TwxObject svc = new TwxObject("1." + A, "Flow", "process", "v",
                "<process><item><processItemId>S</processItemId><name>Anfang</name>"
                        + "<tWComponentName>Start</tWComponentName><TWComponent/></item></process>", app);
        app.objects.put(svc.id, svc);
        assertTrue(run("R-JS-026", new RuleContext(m, Map.of(), false)).isEmpty());
    }

    // ---------- R-JS-027: division by zero ----------

    @Test
    void divisionByNullDefinite() {
        assertEquals(1, run("R-JS-027", "var q = 10 / 0;").size());
        List<Finding> prop = run("R-JS-027", "var n = 0;\nvar q = 10 / n;");
        assertEquals(1, prop.size(), messages(prop));
        assertTrue(prop.get(0).message.contains("'n' was set to 0"));
        // compound assignment "/=" with a known divisor
        assertEquals(1, run("R-JS-027", "var n = 0;\nvar t = 7;\nt /= n;").size());
        // conditional reassignment discards the knowledge (straight-line)
        assertTrue(run("R-JS-027", "var n = 0;\nif (tw.local.ok) { n = 1; }\nvar q = 10 / n;").isEmpty());
        assertTrue(run("R-JS-027", "var n = 2;\nvar q = 10 / (n * 0 + 1);").isEmpty());
    }

    // ---------- R-JS-028: access to null/undefined ----------

    @Test
    void nullAccessDefinite() {
        assertEquals(1, run("R-JS-028", "var o = null;\no.foo;").size());
        assertEquals(1, run("R-JS-028", "var o = undefined;\no.foo;").size());
        assertEquals(1, run("R-JS-028", "var o = null;\no[0];").size());
        // var o; is only hoisted, assigned later — no finding
        assertTrue(run("R-JS-028", "var o;\no = compute();\no.foo;").isEmpty());
    }

    // ---------- R-JS-029: eval on process data ----------

    @Test
    void evalWithTwLocal() {
        assertEquals(1, run("R-JS-029", "eval(tw.local.code);").size());
        assertEquals(1, run("R-JS-029", "var f = new Function('return ' + tw.local.expr);").size());
        assertTrue(run("R-JS-029", "eval('2 + 2');").isEmpty());
        assertTrue(run("R-JS-029", "var o = JSON.parse(tw.local.json);").isEmpty());
    }

    // ---------- R-JS-030: infinite loops ----------

    @Test
    void loopsWithoutExit() {
        assertEquals(1, run("R-JS-030", "while (true) { tw.local.ok = 1; }").size());
        assertTrue(run("R-JS-030", "while (true) { if (x) break; }").isEmpty());
        assertTrue(run("R-JS-030", "outer: while (true) { while (true) { break outer; } }").isEmpty());
        assertTrue(run("R-JS-030", "for (;;) { return 1; }").isEmpty());
        assertEquals(1, run("R-JS-030", "for (;;) { x++; }").size());
        assertEquals(1, run("R-JS-030", "do { y(); } while (true);").size());
        // return in an inner function is not an exit
        assertEquals(1, run("R-JS-030", "while (true) { (function () { return; })(); }").size());
        // conditional loop is not "always true"
        assertTrue(run("R-JS-030", "while (tw.local.ok) { x(); }").isEmpty());
    }

    // ---------- R-JS-031/047: misc ----------

    @Test
    void debuggerStatement() {
        assertEquals(1, run("R-JS-031", "var a = 1;\ndebugger;\n").size());
        assertTrue(run("R-JS-031", "var debugged = 1;").isEmpty());
    }

    @Test
    void parseIntWithoutRadix() {
        List<Finding> f = run("R-JS-032", "var a = parseInt(s);\nvar b = parseInt(s);\nvar c = parseInt(s, 10);");
        assertEquals(1, f.size(), messages(f));                 // one finding per script
        assertTrue(f.get(0).message.contains("(2 occurrences)"));
        assertTrue(run("R-JS-032", "var c = parseInt(s, 10);").isEmpty());
        // own function with the same name is not parseInt (scope resolvable)
        assertTrue(run("R-JS-032", "function parseInt(x) { return x; }\nparseInt('7');").isEmpty());
    }

    // ---------- R-JS-033: hardcoded codes ----------

    @Test
    void hartcodierteCodes() {
        List<Finding> f = run("R-JS-033",
                "if (tw.local.status === 'A') { c = 'XX'; } l = 'DE'; m = { 'KEY': 'x' };");
        assertEquals(1, f.size(), messages(f));
        assertTrue(f.get(0).message.contains("[A, XX]"));   // DE/NY = language code, KEY = object key
        assertTrue(run("R-JS-033", "var lower = 'abc'; var num = '1234567';").isEmpty());
    }

    // ---------- R-JS-034: SQL via concatenation ----------

    @Test
    void sqlConcatenation() {
        assertEquals(1, run("R-JS-034", "var sql = 'SELECT * FROM T WHERE id=' + id;").size());
        assertEquals(1, run("R-JS-034", "stmt = 'DELETE FROM T'; stmt += ' WHERE x=1';").size());
        assertEquals(1, run("R-JS-034", "db.query('SELECT 1 FROM ' + t);").size());
        assertTrue(run("R-JS-034", "var sql = STATIC_SQL; db.execute(sql);").isEmpty());
    }

    // ---------- R-UI-001: Coach-View-Namen ----------

    static RuleContext coachCtx(String cvXml) {
        TwxPackage app = new TwxPackage();
        app.id = "app"; app.name = "Demo"; app.acronym = "DEMO";
        TwxModel m = new TwxModel();
        m.app = app;
        TwxObject cv = new TwxObject("64." + A, "CV", "coachView", "v", cvXml, app);
        app.objects.put(cv.id, cv);
        TwxObject asset = new TwxObject("61.numeral", "numeral.min.js", "managedAsset", "v",
                "<twObject><assetTypeCode>W</assetTypeCode></twObject>", app);
        app.objects.put(asset.id, asset);
        app.jsFiles.put(asset.id, "function numeral(x) { return x; }");
        return new RuleContext(m, Map.of(), false);
    }

    @Test
    void coachViewUnknownNamesWithAndWithoutAssetHint() {
        String mitResource = "<twObject>"
                + "<loadJsFunction>ghost(); known(); numeral(1);</loadJsFunction>"
                + "<amdDependency><functionArgument>known</functionArgument></amdDependency>"
                + "<resource><assetUuid>proj/61.numeral</assetUuid></resource>"
                + "</twObject>";
        List<Finding> f = run("R-UI-001", coachCtx(mitResource));
        assertEquals(1, f.size(), messages(f));
        assertTrue(f.get(0).message.contains("ghost"));
        assertFalse(f.get(0).message.contains("is defined in web asset"));  // the included asset knows the name

        String ohneResource = "<twObject><loadJsFunction>numeral(1);</loadJsFunction></twObject>";
        List<Finding> h = run("R-UI-001", coachCtx(ohneResource));
        assertEquals(1, h.size(), messages(h));
        assertTrue(h.get(0).message.contains("numeral.min.js which this view does not include"),
                messages(h));
    }

    @Test
    void coachViewInlineScriptAndConfigOptions() {
        String cvXml = "<twObject>"
                + "<configOption name=\"meineOption\"><label>L</label></configOption>"
                + "<inlineScript name=\"h\"><scriptType>JS</scriptType>"
                + "<scriptBlock>var local = 1; meineOption; local; this.context;</scriptBlock></inlineScript>"
                + "<inlineScript name=\"s\"><scriptType>CSS</scriptType><scriptBlock>.x{}</scriptBlock></inlineScript>"
                + "</twObject>";
        assertTrue(run("R-UI-001", coachCtx(cvXml)).isEmpty());
    }

    // ---------- Parser and helpers directly ----------

    @Test
    void parserAndHelper() {
        RuleContext c = ctx("var keep = 1; ghost();", null);
        var scripts = c.allScripts();
        assertEquals(1, scripts.size());
        var p = AstRules.parse(scripts.get(0));
        assertNotNull(p);
        assertTrue(AstRules.topLevelNames(p).contains("keep"));
        assertTrue(AstRules.assignedNames(p).isEmpty());
        AstRules.parsed(c);   // fills the cache
        assertSame(AstRules.parsed(c), AstRules.parsed(c));
        assertEquals(1, c.scriptsParsed);
        assertEquals(0, c.scriptsWithSyntaxErrors);
        assertEquals("61.x", AstRules.assetId("projektuuid/61.x"));
        assertEquals("61.x", AstRules.assetId("61.x"));
        // server and client assignment by object type and script kind
        assertTrue(AstRules.isServer(scripts.get(0)));
        assertFalse(AstRules.isClient(scripts.get(0)));
    }

    @Test
    void syntaxErrorIsCountedAndSkips() {
        RuleContext c = ctx("var broken = ( ;", null);
        assertTrue(run("R-JS-027", c).isEmpty());
        assertEquals(1, c.scriptsParsed);
        assertEquals(1, c.scriptsWithSyntaxErrors);
    }
}

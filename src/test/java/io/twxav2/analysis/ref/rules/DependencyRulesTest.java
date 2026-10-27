package io.twxav2.analysis.ref.rules;

import io.twxav2.analysis.ref.model.TwxModel;
import io.twxav2.analysis.ref.model.TwxObject;
import io.twxav2.analysis.ref.model.TwxPackage;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Dependency rules with synthetic models. Focus R-DEP-006:
 * business object TYPE references (prefix 12., FP audit 2026) are not
 * broken artifact references — the class was the biggest false-positive driver.
 */
class DependencyRulesTest {

    static final String MISS = "00000000-0000-4000-8000-000000000001";
    static final String MISS2 = "00000000-0000-4000-8000-000000000002";
    static final String A = "11111111-1111-4111-8111-111111111111";
    static final String B = "22222222-2222-4222-8222-222222222222";

    static Rule rule(String id) {
        for (Rule r : DependencyRules.rules()) if (r.id.equals(id)) return r;
        throw new IllegalStateException("Regel nicht gefunden: " + id);
    }

    static List<Finding> run(Rule r, TwxModel m) {
        List<Finding> out = new ArrayList<>();
        r.check(new RuleContext(m, Map.of(), false), out);
        return out;
    }

    static TwxPackage pkg(String id, String acronym) {
        TwxPackage p = new TwxPackage();
        p.id = id; p.name = "Paket " + acronym; p.acronym = acronym; p.snapshotId = id + "@snap";
        return p;
    }

    static TwxObject obj(TwxPackage p, String id, String name, String type, String xml) {
        TwxObject o = new TwxObject(id, name, type, id + "@v", xml, p);
        p.objects.put(id, o);
        return o;
    }

    static TwxModel model(TwxPackage app, TwxPackage... toolkits) {
        TwxModel m = new TwxModel();
        m.app = app;
        for (TwxPackage t : toolkits) m.toolkits.put(t.snapshotId, t);
        return m;
    }

    static String svc(String head, String items, String links) {
        return "<process>" + head + items + links + "</process>";
    }

    // ---------- R-DEP-006 / R-DEP-006 ----------

    @Test
    void missingArtifactReferenceIsReported() {
        TwxPackage app = pkg("app", "APP");
        obj(app, "1." + A, "Dienst A", "process",
                svc("", "<item><attachedProcessRef>1." + MISS + "</attachedProcessRef></item>", ""));
        List<Finding> f = run(rule("R-DEP-006"), model(app));
        assertEquals(1, f.size());
        assertEquals("1." + A, f.get(0).objectId); // finding attaches to the checked object
        assertTrue(f.get(0).message.contains("1 reference(s)"));
        assertTrue(f.get(0).evidence.contains("1." + MISS));
    }

    @Test
    void typeRefs12AreNotReported() {
        // FP audit: classId/classRef and itm. inline types (evaluatesToTypeRef="itm.12.<uuid>")
        // live snapshot-internally or in the server type registry and are missing from the export.
        TwxPackage app = pkg("app", "APP");
        obj(app, "1." + A, "Dienst A", "process", svc("",
                "<classId>12." + MISS + "</classId>"
                        + "<item evaluatesToTypeRef=\"itm.12." + MISS2 + "\"></item>", ""));
        assertTrue(run(rule("R-DEP-006"), model(app)).isEmpty());
    }

    @Test
    void systemPrefixesOutsideTheArtifactCheck() {
        TwxPackage app = pkg("app", "APP");
        obj(app, "1." + A, "Dienst A", "process", svc("",
                "<a>65." + MISS + "</a><b>70." + MISS + "</b><c>2064." + MISS + "</c>", ""));
        assertTrue(run(rule("R-DEP-006"), model(app)).isEmpty());
    }

    @Test
    void furtherArtifactPrefixesAreReported() {
        TwxPackage app = pkg("app", "APP");
        obj(app, "1." + A, "Dienst A", "process", svc("",
                "<a>25." + MISS + "</a><b>64." + MISS + "</b><c>61." + MISS + "</c>", ""));
        List<Finding> f = run(rule("R-DEP-006"), model(app));
        assertEquals(1, f.size()); // one report per object
        assertTrue(f.get(0).message.startsWith("3 reference(s)"));
    }

    @Test
    void ownIdAndDesignerArtifactsExcluded() {
        TwxPackage app = pkg("app", "APP");
        obj(app, "1." + A, "Dienst A", "process", svc("", "<a>1." + A + "</a><b>25." + MISS + "</b>", ""));
        obj(app, "4." + B, "Layout", "artifact", svc("", "<a>1." + MISS + "</a>", ""));
        obj(app, "47." + B, "Beitrag", "contribution", svc("", "<a>1." + MISS2 + "</a>", ""));
        List<Finding> f = run(rule("R-DEP-006"), model(app));
        assertEquals(1, f.size()); // self-references don't count; artifact/contribution are skipped
        assertEquals("Dienst A", f.get(0).objectName);
        assertTrue(f.get(0).evidence.contains("25." + MISS));
    }

    @Test
    void artifactPrefixMatrix() {
        for (String p : new String[]{"1", "25", "64", "12", "61", "21", "24", "50", "4", "62", "47"})
            assertTrue(DependencyRules.isArtifactPrefix(p + "." + A), p + " sollte als Artefakt-Praefix gelten");
        for (String p : new String[]{"65", "66", "70", "73", "301"})
            assertFalse(DependencyRules.isArtifactPrefix(p + "." + A), p + " ist kein Artefakt-Praefix");
    }

    @Test
    void exposedServicesCountAsUsed() {
        TwxPackage p = pkg("app", "APP");
        assertTrue(DependencyRules.exposed(obj(p, "1." + A, "x", "process", "<exposedType>1</exposedType>")));
        assertTrue(DependencyRules.exposed(obj(p, "1." + B, "x", "process", "<participantRef>t</participantRef>")));
        assertFalse(DependencyRules.exposed(obj(p, "1." + A, "x", "process", "<exposedType isNull=\"true\"/>")));
        assertFalse(DependencyRules.exposed(obj(p, "1." + B, "x", "process", "<service/>")));
    }

    // ---------- cycle detection ----------

    @Test
    void cycleRecognizesDirectAndSelfCycle() {
        Map<String, List<String>> g = new HashMap<>();
        g.put("A", List.of("B"));
        g.put("B", List.of("A"));
        List<List<String>> cyc = DependencyRules.cycles(g);
        assertEquals(1, cyc.size()); // canonical form: only one rotation
        assertEquals(2, cyc.get(0).size());

        Map<String, List<String>> self = new HashMap<>();
        self.put("A", List.of("A"));
        assertEquals(List.of(List.of("A")), DependencyRules.cycles(self));

        Map<String, List<String>> frei = new HashMap<>();
        frei.put("A", List.of("B"));
        frei.put("B", List.of("C"));
        frei.put("C", List.of());
        assertTrue(DependencyRules.cycles(frei).isEmpty());
    }

    @Test
    void toolboxCycle() {
        TwxPackage app = pkg("app", "APP"), t1 = pkg("t1", "TK1"), t2 = pkg("t2", "TK2");
        TwxPackage.Dependency d1 = new TwxPackage.Dependency(); d1.projectId = "t2"; t1.dependencies.add(d1);
        TwxPackage.Dependency d2 = new TwxPackage.Dependency(); d2.projectId = "t1"; t2.dependencies.add(d2);
        List<Finding> f = run(rule("R-DEP-001"), model(app, t1, t2));
        assertEquals(1, f.size());
        assertTrue(f.get(0).message.contains("Paket TK1 -> Paket TK2") || f.get(0).message.contains("Paket TK2 -> Paket TK1"));
    }

    @Test
    void serviceCallCycle() {
        TwxPackage app = pkg("app", "APP");
        obj(app, "1." + A, "Dienst A", "process", svc("<processType>12</processType>",
                "<item><processItemId>I1</processItemId><tWComponentName>SubProcess</tWComponentName>"
                        + "<TWComponent><attachedProcessRef>1." + B + "</attachedProcessRef></TWComponent></item>", ""));
        obj(app, "1." + B, "Dienst B", "process", svc("<processType>12</processType>",
                "<item><processItemId>I1</processItemId><tWComponentName>SubProcess</tWComponentName>"
                        + "<TWComponent><attachedProcessRef>1." + A + "</attachedProcessRef></TWComponent></item>", ""));
        List<Finding> f = run(rule("R-DEP-002"), model(app));
        assertEquals(1, f.size());
        assertTrue(f.get(0).message.contains("Dienst A"));
        assertTrue(f.get(0).message.contains("Dienst B"));
    }

    // ---------- unused objects ----------

    @Test
    void unusedServiceReportedExceptionsFree() {
        TwxPackage app = pkg("app", "APP"), tk = pkg("tk", "TK");
        obj(app, "1." + A, "Tot", "process", svc("<processType>6</processType>", "", ""));
        obj(app, "1." + B, "Deployment", "process", svc("<processType>12</processType>", "", ""));
        TwxModel m = model(app, tk);
        List<Finding> f = run(rule("R-DEP-003"), m);
        assertEquals(1, f.size());
        assertEquals("Tot", f.get(0).objectName);

        // referenced (from a toolkit object) -> no finding
        obj(tk, "1." + MISS, "TK-Dienst", "process", svc("", "<a>1." + A + "</a>", ""));
        assertTrue(run(rule("R-DEP-003"), m).isEmpty());
    }

    @Test
    void unusedBusinessObjectAndCoachView() {
        TwxPackage app = pkg("app", "APP");
        obj(app, "12." + A, "Vielverwendet", "twClass", "<twClass/>");
        obj(app, "12." + B, "Verwaist", "twClass", "<twClass/>");
        obj(app, "64." + A, "Datenhalter", "process", svc("", "<a>12." + A + "</a>", ""));
        List<Finding> f = run(rule("R-DEP-004"), model(app));
        assertEquals(1, f.size());
        assertEquals("Verwaist", f.get(0).objectName);

        obj(app, "64." + B, "UI-Baustein", "coachView", "<coachView/>");
        List<Finding> cv = run(rule("R-DEP-005"), model(app));
        assertEquals(1, cv.size());
        assertEquals("UI-Baustein", cv.get(0).objectName);
    }

    @Test
    void referencedByIndex() {
        TwxPackage app = pkg("app", "APP");
        obj(app, "1." + A, "Ziel", "process", "<process/>");
        obj(app, "1." + B, "Quell", "process", svc("", "<a>1." + A + "</a>", ""));
        RuleContext c = new RuleContext(model(app), new LinkedHashMap<>(), false);
        assertEquals(1, c.referencedBy("1." + A).size());
        assertTrue(c.referencedBy("1." + B).isEmpty());
        assertEquals("Ziel", c.nameOf("1." + A));
        assertEquals("1." + MISS, c.nameOf("1." + MISS)); // unknown -> id returned
    }
}

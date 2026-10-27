package io.twxav2.analysis.ref.rules;

import io.twxav2.analysis.ref.model.ServiceModel;
import io.twxav2.analysis.ref.model.TwxModel;
import io.twxav2.analysis.ref.model.TwxObject;
import io.twxav2.analysis.ref.model.TwxPackage;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Service rules with synthetic service flows. Focus R-SVC-001:
 * exception/end nodes are terminating (no "missing outgoing edge"),
 * step error handling counts as incoming for the handler step, and
 * BPMN2 sequence flows (sourceRef/targetRef) are counted too — the three
 * false-positive classes of the FP audit 2026.
 */
class ServiceRulesTest {

    static final String A = "11111111-1111-4111-8111-111111111111";
    static final String B = "22222222-2222-4222-8222-222222222222";
    static final String C = "33333333-3333-4333-8333-333333333333";
    static final String D = "44444444-4444-4444-8444-444444444444";

    static Rule rule(String id) {
        for (Rule r : ServiceRules.rules()) if (r.id.equals(id)) return r;
        throw new IllegalStateException("Regel nicht gefunden: " + id);
    }

    static List<Finding> run(Rule r, TwxObject... objs) {
        TwxPackage app = new TwxPackage();
        app.id = "app"; app.name = "Demo"; app.acronym = "DEMO"; app.snapshotId = "app@snap";
        TwxModel m = new TwxModel();
        m.app = app;
        for (TwxObject o : objs) {
            TwxObject withPkg = new TwxObject(o.id, o.name, o.type, o.versionId, o.xml, app);
            app.objects.put(withPkg.id, withPkg);
        }
        List<Finding> out = new ArrayList<>();
        r.check(new RuleContext(m, Map.of(), false), out);
        return out;
    }

    static TwxObject svc(String id, String name, String head, String items, String... links) {
        return new TwxObject(id, name, "process", id + "@v",
                "<process>" + head + items + String.join("", links) + "</process>", null);
    }

    static String item(String id, String name, String comp, String inner) {
        return "<item><processItemId>" + id + "</processItemId><name>" + name + "</name>"
                + "<tWComponentName>" + comp + "</tWComponentName><TWComponent>" + inner + "</TWComponent></item>";
    }

    static String item(String id, String name, String comp) {
        return item(id, name, comp, "");
    }

    static String link(String from, String to) {
        return link(null, from, to, "", "");
    }

    static String link(String name, String from, String to, String endState, String condition) {
        return "<link" + (name == null ? "" : " name=\"" + name + "\"") + "><processLinkId>L-" + from + "-" + to + "</processLinkId>"
                + "<fromProcessItemId>" + from + "</fromProcessItemId><toProcessItemId>" + to + "</toProcessItemId>"
                + "<endStateId>" + endState + "</endStateId><condition>" + condition + "</condition></link>";
    }

    // ---------- R-SVC-001 wiring ----------

    @Test
    void exceptionAndEndNodesAreTerminating() {
        // an exception node without an outgoing edge is not a wiring error.
        List<Finding> f = run(rule("R-SVC-001"), svc("1." + A, "Flow", "<processType>12</processType>",
                item("S", "Start", "Start") + item("X", "Verarbeite", "Script", "<script>x=1;</script>")
                        + item("E", "Fehler", "Exception"),
                link("S", "X"), link("X", "E")));
        assertTrue(f.isEmpty(), () -> "Erwartung: keine Befunde, kam: " + f.stream().map(x -> x.message).toList());
    }

    @Test
    void stepErrorHandlingCountsAsIncoming() {
        String handler = "<isErrorHandlerEnabled>true</isErrorHandlerEnabled><errorHandlerItemId>H</errorHandlerItemId>";
        List<Finding> mit = run(rule("R-SVC-001"), svc("1." + A, "Flow", "<processType>12</processType>",
                item("S", "Start", "Start") + item("X", "Aufruf", "Script", handler)
                        + item("H", "Fehlerbehandlung", "Script", "<script>log();</script>") + item("E", "Ende", "ExitPoint"),
                link("S", "X"), link("X", "E"), link("H", "E")));
        assertTrue(mit.isEmpty(), () -> "Handler hat Eingang durch Fehlerwurf: " + mit.stream().map(x -> x.message).toList());

        List<Finding> ohne = run(rule("R-SVC-001"), svc("1." + A, "Flow", "<processType>12</processType>",
                item("S", "Start", "Start") + item("X", "Aufruf", "Script")
                        + item("H", "Fehlerbehandlung", "Script", "<script>log();</script>") + item("E", "Ende", "ExitPoint"),
                link("S", "X"), link("X", "E"), link("H", "E")));
        assertEquals(1, ohne.size());
        assertTrue(ohne.get(0).message.contains("no incoming link"));
    }

    @Test
    void bpmn2SequenceFlowsCountAsEdges() {
        // JSON-style sourceRef/targetRef (ServiceModel only knows the link blocks).
        List<Finding> f = run(rule("R-SVC-001"), svc("1." + A, "Flow",
                "<processType>12</processType>",
                item("1." + B, "Start", "Start") + item("1." + C, "Tun", "Script", "<script>x=1;</script>")
                        + item("1." + D, "Ende", "ExitPoint")
                        + "<bpmn>{\"sequenceFlow\":{\"sourceRef\":\"1." + B + "\",\"targetRef\":\"1." + C + "\"}},"
                        + "{\"sourceRef\":\"1." + C + "\",\"targetRef\":\"1." + D + "\"}</bpmn>"));
        assertTrue(f.isEmpty(), () -> "Sequenzfluesse verbinden die Schritte: " + f.stream().map(x -> x.message).toList());
    }

    @Test
    void realWiringErrorPersists() {
        List<Finding> f = run(rule("R-SVC-001"), svc("1." + A, "Flow", "<processType>12</processType>",
                item("S", "Start", "Start") + item("X", "Verloren", "Script", "<script>x=1;</script>")
                        + item("E", "Ende", "ExitPoint"),
                link("S", "E")));
        assertEquals(1, f.size());
        assertTrue(f.get(0).message.contains("keine") || f.get(0).message.contains("no incoming"));
        assertEquals("X", f.get(0).itemId);
    }

    @Test
    void servicesWithoutStartStep() {
        List<Finding> f = run(rule("R-SVC-002"), svc("1." + A, "Flow", "<processType>12</processType>",
                item("S", "Tun", "Script", "<script>x=1;</script>")));
        assertEquals(1, f.size());
        assertTrue(f.get(0).message.contains("No start step"));
    }

    // ---------- further rules ----------

    @Test
    void emptyStepsAndMissingCall() {
        List<Finding> f = run(rule("R-SVC-003"), svc("1." + A, "Flow", "<processType>12</processType>",
                item("S", "Leer", "Script") + item("K", "Aufruf", "SubProcess")
                        + item("G", "Gut", "Script", "<script>x=1;</script>")));
        assertEquals(2, f.size());
    }

    @Test
    void callMissingInExport() {
        List<Finding> f = run(rule("R-SVC-004"), svc("1." + A, "Flow", "<processType>12</processType>",
                item("K", "Aufruf", "SubProcess", "<attachedProcessRef>1." + B + "</attachedProcessRef>")));
        assertEquals(1, f.size());
        assertTrue(f.get(0).message.contains("missing artifact"));
    }

    @Test
    void loopWithoutExit() {
        TwxObject ohneExit = svc("1." + A, "Flow", "<processType>12</processType>",
                item("S", "Start", "Start") + item("X", "A", "Script", "<script>x=1;</script>")
                        + item("Y", "B", "Script", "<script>x=2;</script>"),
                link("S", "X"), link("X", "Y"), link("Y", "X"));
        assertEquals(1, run(rule("R-SVC-005"), ohneExit).size());

        TwxObject mitExit = svc("1." + A, "Flow", "<processType>12</processType>",
                item("S", "Start", "Start") + item("X", "A", "Script", "<script>x=1;</script>")
                        + item("Y", "B", "Script", "<script>x=2;</script>") + item("E", "Ende", "ExitPoint"),
                link("S", "X"), link("X", "Y"), link("Y", "X"), link("Y", "E"));
        assertTrue(run(rule("R-SVC-005"), mitExit).isEmpty());
    }

    @Test
    void switchWithoutDefaultPath() {
        TwxObject ohneDefault = svc("1." + A, "Flow", "<processType>12</processType>",
                item("S", "Start", "Start") + item("W", "Weiche", "Switch") + item("E1", "E1", "ExitPoint") + item("E2", "E2", "ExitPoint"),
                link("S", "W"), link("ja", "W", "E1", "ES1", "tw.local.x == 1"), link("nein", "W", "E2", "ES2", "tw.local.x == 2"));
        assertEquals(1, run(rule("R-SVC-013"), ohneDefault).size());

        TwxObject mitDefault = svc("1." + A, "Flow", "<processType>12</processType>",
                item("S", "Start", "Start") + item("W", "Weiche", "Switch") + item("E1", "E1", "ExitPoint") + item("E2", "E2", "ExitPoint"),
                link("S", "W"), link("ja", "W", "E1", "ES1", "tw.local.x == 1"), link("default", "W", "E2", "", ""));
        assertTrue(run(rule("R-SVC-013"), mitDefault).isEmpty());
    }

    @Test
    void docsAndCamelCase() {
        List<Finding> doku = run(rule("R-SVC-015"), svc("1." + A, "Flow", "<processType>12</processType>",
                item("S", "Tun", "Script", "<script>x=1;</script>")));
        assertEquals(1, doku.size());

        List<Finding> namen = run(rule("R-SVC-016"), svc("1." + A, "Flow", "<processType>12</processType>",
                "<processVariable name=\"Bad_Name\"><processVariableId>V1</processVariableId></processVariable>"
                        + "<processVariable name=\"okName\"><processVariableId>V2</processVariableId></processVariable>"
                        + item("S", "Tun", "Script", "<script>tw.local.okName=1;</script>")));
        assertEquals(1, namen.size());
        assertTrue(namen.get(0).message.contains("Bad_Name"));
    }

    @Test
    void errorLinksAndReachabilityHelper() {
        TwxObject o = svc("1." + A, "Flow", "<processType>6</processType>",
                item("S", "Start", "Start") + item("K", "Aufruf", "WebServiceConnector") + item("E", "Ende", "ExitPoint"),
                link("S", "K"), link("Error", "K", "E", "ES-Error", ""));
        ServiceModel s = new RuleContext(modelOf(o), Map.of(), false).service(o);
        ServiceModel.Item k = s.item("K");
        assertTrue(ServiceRules.hasErrorLink(s, k));
        assertTrue(ServiceRules.reaches(s, "K", "E", new HashSet<>()));
        assertFalse(ServiceRules.reaches(s, "E", "K", new HashSet<>()));
        assertEquals(2, ServiceRules.count("tw.local.a tw.local.a tw.local.b", "tw.local.a"));
        // SVC-006: error link present -> no "no error handling" finding
        assertTrue(run(rule("R-SVC-006"), o).isEmpty());
    }

    @Test
    void scriptOnExitDot() {
        List<Finding> f = run(rule("R-SVC-010"), svc("1." + A, "Flow", "<processType>12</processType>",
                item("S", "Start", "Start") + item("E", "Ende", "ExitPoint", "<script>tw.local.out = 1;</script>"),
                link("S", "E")));
        assertEquals(1, f.size());
        assertTrue(f.get(0).message.contains("Exit point"));
    }

    static TwxModel modelOf(TwxObject o) {
        TwxPackage app = new TwxPackage();
        app.id = "app"; app.name = "Demo"; app.acronym = "DEMO"; app.snapshotId = "app@snap";
        app.objects.put(o.id, o);
        TwxModel m = new TwxModel();
        m.app = app;
        return m;
    }
}

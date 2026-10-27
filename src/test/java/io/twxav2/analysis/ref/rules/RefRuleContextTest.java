package io.twxav2.analysis.ref.rules;

import io.twxav2.analysis.ref.model.BusinessObjectModel;
import io.twxav2.analysis.ref.model.TwxModel;
import io.twxav2.analysis.ref.model.TwxObject;
import io.twxav2.analysis.ref.model.TwxPackage;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Shared rule context: thresholds, package selection (app only vs. with
 * non-system toolkits), model caches, declared tw.local variables
 * including embedded subprocesses, reference index and type names.
 */
class RefRuleContextTest {

    private static TwxPackage pkg(String acronym, boolean toolkit) {
        TwxPackage p = new TwxPackage();
        p.name = "Paket " + acronym;
        p.acronym = acronym;
        p.toolkit = toolkit;
        return p;
    }

    private static TwxObject add(TwxPackage p, String id, String name, String type, String xml) {
        TwxObject o = new TwxObject(id, name, type, "snap-1", xml, p);
        p.objects.put(id, o);
        return o;
    }

    private static final String SVC = """
            <process><processType>4</processType>
              <serviceVariable name="svar"><classId>/12.a</classId></serviceVariable>
            </process>""";

    private static TwxModel model() {
        TwxModel m = new TwxModel();
        m.app = pkg("DEMO", false);
        add(m.app, "1.svc", "Svc", "process", SVC);
        add(m.app, "25.top", "Top", "bpd",
                "<bpd><privateVariable id=\"v1\"><name>topVar</name></privateVariable>"
                        + "<flowObject id=\"f\"><name>Teil</name>"
                        + "<embeddedProcessId>25.sub</embeddedProcessId></flowObject></bpd>");
        add(m.app, "25.sub", "Sub", "bpd",
                "<bpd><privateVariable id=\"v2\"><name>subVar</name></privateVariable></bpd>");
        add(m.app, "64.cv", "CV", "coachView", "<twObject><coachView/></twObject>");
        add(m.app, "12.bo", "BO", "twClass", "<twObject><jsonData>{}</jsonData></twObject>");
        add(m.app, "11.uca", "UCA", "underCoverAgent", "<twObject/>");
        m.toolkits.put("tk-biz", bizToolkit());
        m.toolkits.put("tk-sys", sysToolkit());
        return m;
    }

    private static TwxPackage bizToolkit() {
        TwxPackage tk = pkg("BIZKIT", true);
        add(tk, "1.tksvc", "TK Svc", "process", "<process><serviceVariable name=\"tkvar\"/></process>");
        return tk;
    }

    private static TwxPackage sysToolkit() {
        TwxPackage tk = pkg("TWSYS", true);
        add(tk, "1.sysobj", "Sys", "process", "<process/>");
        return tk;
    }

    // ---------- thresholds ----------

    @Test
    void thresholdFromCountTextOrDefault() {
        Map<String, Object> s = new HashMap<>();
        s.put("n", 7);
        s.put("text", "42");
        s.put("muell", "abc");
        RuleContext c = new RuleContext(model(), s, false);
        assertEquals(7, c.threshold("n", 1));
        assertEquals(42, c.threshold("text", 1));
        assertEquals(1, c.threshold("muell", 1));
        assertEquals(5, c.threshold("unbekannt", 5));
    }

    @Test
    void nullSettingsAreEmptyMap() {
        RuleContext c = new RuleContext(model(), null, false);
        assertTrue(c.settings.isEmpty());
    }

    // ---------- package and object selection ----------

    @Test
    void packagesWithoutAndWithToolkits() {
        RuleContext ohne = new RuleContext(model(), null, false);
        assertEquals(1, ohne.packages().size());
        assertEquals(6, ohne.objects(null).size());
        assertEquals(1, ohne.objects("process").size());
        assertEquals(2, ohne.objects("bpd").size());
        assertTrue(ohne.objects("nix").isEmpty());

        RuleContext mit = new RuleContext(model(), null, true);
        assertEquals(2, mit.packages().size());   // TWSYS drops out as a system toolkit
        assertEquals(7, mit.objects(null).size());
    }

    @Test
    void systemPackagesAtChangeRecognized() {
        RuleContext c = new RuleContext(model(), null, true);
        assertTrue(c.isSystem(pkg("TWSYS", true)));
        assertTrue(c.isSystem(pkg("SYSANY", true)));
        assertTrue(c.isSystem(pkg("BPMUI", true)));
        assertTrue(c.isSystem(pkg("SYSCM", true)));
        assertTrue(c.isSystem(pkg("SYSRTC", true)));
        assertFalse(c.isSystem(pkg("FACHKIT", true)));
    }

    // ---------- model caches ----------

    @Test
    void modelsArePerObjectSaved() {
        RuleContext c = new RuleContext(model(), null, false);
        TwxObject svc = c.twx.app.objects.get("1.svc");
        assertSame(c.service(svc), c.service(svc));
        TwxObject bpd = c.twx.app.objects.get("25.top");
        assertSame(c.bpd(bpd), c.bpd(bpd));
        TwxObject cv = c.twx.app.objects.get("64.cv");
        assertSame(c.coachView(cv), c.coachView(cv));
        TwxObject bo = c.twx.app.objects.get("12.bo");
        BusinessObjectModel m = c.bo(bo);
        assertSame(m, c.bo(bo));
        assertNotNull(m);
    }

    @Test
    void scriptCacheAfterObjectType() {
        RuleContext c = new RuleContext(model(), null, false);
        TwxObject uca = c.twx.app.objects.get("11.uca");
        assertTrue(c.scripts(uca).isEmpty());          // neither process nor bpd nor coachView
        assertSame(c.scripts(uca), c.scripts(uca));
        assertTrue(c.allScripts().isEmpty());          // mini fixtures without scripts
    }

    // ---------- declared variables ----------

    @Test
    void subProcessInheritsVariablesOfParent() {
        RuleContext c = new RuleContext(model(), null, false);
        TwxObject sub = c.twx.app.objects.get("25.sub");
        TwxObject top = c.twx.app.objects.get("25.top");
        assertSame(top, c.embeddingBpd(sub));
        assertNull(c.embeddingBpd(top));               // no grandfather
        assertTrue(c.declaredVariables(sub).contains("subVar"));
        assertTrue(c.declaredVariables(sub).contains("topVar"));
        assertFalse(c.declaredVariables(top).contains("subVar"));
        assertSame(c.declaredVariables(sub), c.declaredVariables(sub));   // Cache
    }

    @Test
    void declaredVariablesOfOneServices() {
        RuleContext c = new RuleContext(model(), null, false);
        TwxObject svc = c.twx.app.objects.get("1.svc");
        assertTrue(c.declaredVariables(svc).isEmpty() || c.declaredVariables(svc).contains("svar"));
        assertSame(c.declaredVariables(svc), c.declaredVariables(svc));
    }

    // ---------- reference index and names ----------

    @Test
    void backwardReferencesWithHit() {
        TwxModel m = model();
        m.app.objects.put("1.svc", new TwxObject("1.svc", "Svc", "process", "snap-1",
                "<process><team>/00.11111111-2222-3333-4444-555555555555</team></process>", m.app));
        RuleContext c = new RuleContext(m, null, false);
        var refs = c.referencedBy("00.11111111-2222-3333-4444-555555555555");
        assertTrue(refs.contains("1.svc"));
        assertSame(c.referencedBy(), c.referencedBy());   // Index-Cache
        assertTrue(c.referencedBy("00.unbekannt").isEmpty());
    }

    @Test
    void namesAndTypesFromReferences() {
        RuleContext c = new RuleContext(model(), null, false);
        assertEquals("Svc", c.nameOf("1.svc"));
        assertEquals("pfad/unbekannt", c.nameOf("pfad/unbekannt"));   // lookup miss -> reference returned
        // found object: name
        assertEquals("BO", c.typeName("12.bo"));
        assertEquals("", c.typeName(null));
        // system types by bare UUID
        assertEquals("String", c.typeName("/12.db884a3c-c533-44b7-bb2d-47bec8ad4022"));
        assertEquals("Map", c.typeName("12.06fc5986-1156-4005-ac19-1d0b535b7fce"));
        // unknown without a hit: bare part after the last slash
        assertEquals("12.unbekannt", c.typeName("12.tief/12.unbekannt"));
    }
}

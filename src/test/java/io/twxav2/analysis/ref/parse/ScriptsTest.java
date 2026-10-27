package io.twxav2.analysis.ref.parse;

import io.twxav2.analysis.ref.model.BpdModel;
import io.twxav2.analysis.ref.model.CoachViewModel;
import io.twxav2.analysis.ref.model.Script;
import io.twxav2.analysis.ref.model.ServiceModel;
import io.twxav2.analysis.ref.model.TwxObject;
import io.twxav2.analysis.ref.model.TwxPackage;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Scripts.of collects all JS snippets of an object with their location — service steps,
 * mappings, assignments, link/flow conditions, default values, coach layout,
 * BPD activities and coach view scripts. Fixtures are directly built models.
 */
class ScriptsTest {

    private static TwxObject obj(String id, String name, String type) {
        TwxPackage pkg = new TwxPackage();
        pkg.name = "Demo"; pkg.acronym = "DEMO";
        TwxObject o = new TwxObject(id, name, type, "snap-1", "<x/>", pkg);
        pkg.objects.put(id, o);
        return o;
    }

    private static ServiceModel.Variable var(String name, String def, boolean code) {
        ServiceModel.Variable v = new ServiceModel.Variable();
        v.name = name;
        v.hasDefault = true;
        v.defaultValue = def;
        if (!code) {
            v.hasDefault = code;    // without the flag, no script candidate
        }
        return v;
    }

    // ---------- Service ----------

    @Test
    void serviceStepsMiddleAndAssignments() {
        TwxObject o = obj("1.svc", "Svc", "process");
        ServiceModel svc = new ServiceModel();
        ServiceModel.Item scriptStep = new ServiceModel.Item();
        scriptStep.id = "i1"; scriptStep.name = "Rechne"; scriptStep.component = "Script";
        scriptStep.script = "tw.local.x = 1;";
        ServiceModel.Item leer = new ServiceModel.Item();
        leer.id = "i2"; leer.name = "Kooper"; leer.component = "Coach";
        ServiceModel.Item vorlage = new ServiceModel.Item();
        vorlage.id = "i3"; vorlage.name = "Mail"; vorlage.component = "Mail";
        vorlage.script = "<#= tw.local.x #>"; vorlage.scriptTypeId = "128";
        scriptStep.mappings.add(mapping("p", "f(tw.local.x)", true));
        scriptStep.mappings.add(mapping("q", "tw.local.x", true));   // no code semantics
        scriptStep.preAssignments.add("tw.local.pre = 1;");
        scriptStep.postAssignments.add("tw.local.post = 2;");
        svc.items.add(scriptStep);
        svc.items.add(leer);
        svc.items.add(vorlage);
        ServiceModel.Link link = new ServiceModel.Link();
        link.name = "Weiter"; link.fromItemId = "i1"; link.condition = "tw.local.x > 1";
        ServiceModel.Link linkLeer = new ServiceModel.Link();
        linkLeer.name = "Immer"; linkLeer.condition = "   ";
        svc.links.add(link);
        svc.links.add(linkLeer);
        svc.variables.add(var("code", "new Date()", true));
        svc.variables.add(var("kahl", "5", false));
        svc.parameters.add(var("pdef", "a && b", true));

        var out = Scripts.of(o, svc, null, null);
        var byLoc = out.stream().collect(java.util.stream.Collectors.toMap(s -> s.location, s -> s,
                (a, b) -> a));
        assertEquals("script", byLoc.get("step 'Rechne' (Script)").kind);
        assertEquals("template", byLoc.get("step 'Mail' (Mail)").kind);
        assertFalse(byLoc.containsKey("step 'Kooper' (Coach)"));      // empty script body
        assertTrue(byLoc.containsKey("step 'Rechne' mapping p"));
        assertFalse(byLoc.containsKey("step 'Rechne' mapping q"));    // plain variable reference
        assertEquals("assignment", byLoc.get("step 'Rechne' pre-assignment").kind);
        assertEquals("assignment", byLoc.get("step 'Rechne' post-assignment").kind);
        assertEquals("condition", byLoc.get("link 'Weiter' condition").kind);
        assertFalse(byLoc.containsKey("link 'Immer' condition"));
        assertEquals("expression", byLoc.get("variable 'code' default value").kind);
        assertFalse(byLoc.containsKey("variable 'kahl' default value"));
        assertTrue(byLoc.containsKey("parameter 'pdef' default value"));
    }

    @Test
    void coachLayoutConfigOptionsOnlyWithTwCode() {
        TwxObject o = obj("1.human", "Human", "process");
        ServiceModel svc = new ServiceModel();
        svc.coachLayoutXml = """
                <ns18:coachDefinition>
                  <ns18:configData><ns18:optionName>calc</ns18:optionName>
                    <ns18:value>tw.local.a + 1</ns18:value></ns18:configData>
                  <ns18:configData><ns18:optionName>fixed</ns18:optionName>
                    <ns18:value>5</ns18:value></ns18:configData>
                </ns18:coachDefinition>""";
        var out = Scripts.of(o, svc, null, null);
        assertEquals(1, out.size());
        assertEquals("coach option calc", out.get(0).location);
        assertEquals("expression", out.get(0).kind);
    }

    // ---------- BPD ----------

    @Test
    void bpdActivitiesFlowsAndVariable() {
        TwxObject o = obj("25.b", "BPD", "bpd");
        BpdModel bpd = new BpdModel();
        BpdModel.FlowObject a = new BpdModel.FlowObject();
        a.id = "a1"; a.name = "Aktivitaet"; a.script = "tw.local.y = 2;";
        a.mappings.add(mapping("m", "g()", false));
        BpdModel.FlowObject leer = new BpdModel.FlowObject();
        leer.id = "a2"; leer.name = "Ohne Script"; leer.script = " ";
        bpd.flowObjects.add(a);
        bpd.flowObjects.add(leer);
        BpdModel.Flow fl = new BpdModel.Flow();
        fl.name = "Zweig"; fl.fromId = "a1"; fl.condition = "tw.local.ok";
        BpdModel.Flow flLeer = new BpdModel.Flow();
        flLeer.condition = "";
        bpd.flows.add(fl);
        bpd.flows.add(flLeer);
        bpd.variables.add(var("bv", "tw.utils.x()", true));

        var out = Scripts.of(o, null, bpd, null);
        var byLoc = out.stream().collect(java.util.stream.Collectors.toMap(s -> s.location, s -> s));
        assertEquals("a1", byLoc.get("activity 'Aktivitaet' script").itemId);
        assertEquals("script", byLoc.get("activity 'Aktivitaet' script").kind);
        assertFalse(byLoc.containsKey("activity 'Ohne Script' script"));
        assertTrue(byLoc.containsKey("activity 'Aktivitaet' mapping m"));
        assertEquals("condition", byLoc.get("flow 'Zweig' condition").kind);
        assertEquals("expression", byLoc.get("variable 'bv' default value").kind);
    }

    // ---------- Coach View ----------

    @Test
    void coachViewOnlyJsScripts() {
        TwxObject o = obj("64.cv", "CV", "coachView");
        CoachViewModel cv = new CoachViewModel();
        cv.scripts.add(new CoachViewModel.Script("load", "load", "tw.local.x = 1;"));
        cv.scripts.add(new CoachViewModel.Script("inline", "helper", "var a;"));
        cv.scripts.add(new CoachViewModel.Script("css", "style", ".x{}"));
        cv.scripts.add(new CoachViewModel.Script("html", "tpl", "<div/>"));
        var out = Scripts.of(o, null, null, cv);
        assertEquals(2, out.size());
        assertEquals("handler 'load'", out.get(0).location);
        assertEquals("load", out.get(0).kind);
        assertEquals("inline script 'helper'", out.get(1).location);
    }

    // ---------- looksLikeCode / edge cases ----------

    @Test
    void looksLikeCode() {
        assertTrue(Scripts.looksLikeCode("f()"));
        assertTrue(Scripts.looksLikeCode("a + b"));
        assertTrue(Scripts.looksLikeCode("a ? b : c"));
        assertTrue(Scripts.looksLikeCode("new Date()"));
        assertTrue(Scripts.looksLikeCode("x;"));
        assertTrue(Scripts.looksLikeCode("a[0]"));
        assertTrue(Scripts.looksLikeCode("a && b"));
        assertTrue(Scripts.looksLikeCode("a || b"));
        assertFalse(Scripts.looksLikeCode(null));
        assertFalse(Scripts.looksLikeCode("  "));
        assertFalse(Scripts.looksLikeCode("tw.local.x"));
        assertFalse(Scripts.looksLikeCode("5"));
    }

    @Test
    void withoutModelsNoScripts() {
        TwxObject o = obj("61.x", "Roh", "managedAsset");
        assertTrue(Scripts.of(o, null, null, null).isEmpty());
    }

    @Test
    void scriptCountsLines() {
        TwxObject o = obj("1.z", "Z", "process");
        assertEquals(0, new Script(o, "loc", null, "script", "  ").lines());
        assertEquals(1, new Script(o, "loc", null, "script", "var a;").lines());
        assertEquals(2, new Script(o, "loc", null, "script", "var a;\nvar b;").lines());
    }

    private static ServiceModel.Mapping mapping(String name, String value, boolean input) {
        ServiceModel.Mapping m = new ServiceModel.Mapping();
        m.parameterName = name;
        m.value = value;
        m.input = input;
        return m;
    }
}

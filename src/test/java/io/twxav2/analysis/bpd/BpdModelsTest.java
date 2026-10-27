package io.twxav2.analysis.bpd;

import io.twxav2.analysis.bpd.Bpd.FlowObj;
import io.twxav2.analysis.bpd.Bpd.Node;
import io.twxav2.ingest.TwxParseResult.ParsedObject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static io.twxav2.analysis.ModelsFixtures.obj;
import static io.twxav2.analysis.ModelsFixtures.result;
import static io.twxav2.analysis.ModelsFixtures.uuid;
import static org.junit.jupiter.api.Assertions.*;

/**
 * BPD graph (B3): parsing from flowObjects + bpmn2Data, default resolution,
 * lane merge, reachability, unconditional cycle, longest chain.
 */
class BpdModelsTest {

    private static final String BPD_ID = "25." + uuid('a');

    /** Embed BPMN XML entity-encoded into the bpmn2Data element. */
    private static String embed(String bpmn) {
        return "<bpmn2Data>" + bpmn.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;") + "</bpmn2Data>";
    }

    private static String bpmn(String inner) {
        return "<definitions xmlns:bpmn2=\"http://x\"><process id=\"p\">" + inner + "</process></definitions>";
    }

    private static ParsedObject bpdObject(String twInner, String bpmnInner) {
        return obj(BPD_ID, "BPD", "twBPD", "<twObject><bpd name=\"Vorgang\">"
                + twInner + embed(bpmn(bpmnInner)) + "</bpd></twObject>");
    }

    private static Bpd one(String twInner, String bpmnInner) {
        var list = BpdModels.of(result(List.of(bpdObject(twInner, bpmnInner))));
        assertEquals(1, list.size());
        return list.get(0);
    }

    /** Start -> gate -> (yes, conditional) activity -> end; default at gate; boundary error. */
    private static final String MAIN_BPMN = """
            <startEvent id="s" name="Start"/>
            <exclusiveGateway id="g" name="Weiche" default="f3"/>
            <callActivity id="a1" name="Pruefen">
              <extensionElements><twl:activityType>ServiceTask</twl:activityType></extensionElements>
            </callActivity>
            <boundaryEvent id="be" attachedToRef="a1">
              <errorEventDefinition eventImplId="err1"/>
            </boundaryEvent>
            <endEvent id="e"/>
            <endEvent id="tot"/>
            <sequenceFlow id="f1" sourceRef="s" targetRef="g"/>
            <sequenceFlow id="f2" sourceRef="g" targetRef="a1" name="ja">
              <conditionExpression>tw.local.x == 1</conditionExpression>
            </sequenceFlow>
            <sequenceFlow id="f3" sourceRef="g" targetRef="e"/>
            <sequenceFlow id="f4" sourceRef="a1" targetRef="e"/>
            """;

    // ---------- Parse ----------

    @Test
    void ofReturnsPerParseResultTheSameList() {
        var pr = result(List.of());
        assertSame(BpdModels.of(pr), BpdModels.of(pr));
    }

    @Test
    void onlyReadable25ObjectsWithBpdElement() {
        var pr = result(List.of(
                obj(BPD_ID, "BPD", "twBPD", "<twObject><bpd/></twObject>"),
                obj("1." + uuid('b'), "Svc", "twProcess", "<twObject><bpd/></twObject>"),
                obj("25." + uuid('c'), "Ohne Inhalt", "twBPD", null),
                obj("25." + uuid('d'), "Kaputt", "twBPD", "kein xml <<"),
                obj("25." + uuid('e'), "OhneBpd", "twBPD", "<twObject><anderes/></twObject>")));
        assertEquals(1, BpdModels.of(pr).size());
    }

    @Test
    void masterDataOverviewAndFlowObjects() {
        var bpd = one("""
                <autoTrackingEnabled>TRUE</autoTrackingEnabled>
                <group><searchableField/><searchableField/></group>
                <flowObject id="a1" componentType="Service">
                  <name>Pruefen</name><loopType>2</loopType>
                  <implementation><serviceCall>x</serviceCall><attachedActivityId>sub</attachedActivityId></implementation>
                </flowObject>
                <flowObject id="be" componentType="Event">
                  <isHidden>true</isHidden>
                  <component><implementation><viewTypeId>v</viewTypeId></implementation></component>
                </flowObject>
                """, MAIN_BPMN);
        assertEquals(BPD_ID, bpd.id());
        assertEquals("Vorgang", bpd.name());
        assertTrue(bpd.autoTracking());
        assertEquals(2, bpd.searchableCount());
        FlowObj a1 = bpd.flowObjects().get("a1");
        assertTrue(a1.multiInstance());
        assertEquals("serviceCall,attachedActivityId", a1.implementation());
        assertEquals("sub", a1.attachedActivityId());
        assertFalse(bpd.flowObjects().get("be").emptyImplementation());
        assertTrue(bpd.flowObjects().get("be").hidden());
        assertEquals("viewTypeId", bpd.flowObjects().get("be").implementation());
    }

    @Test
    void flowObjectWithoutImplementationAndJunkLoopType() {
        var bpd = one("<flowObject id=\"x\" componentType=\"View\"/>", "<startEvent id=\"s\"/>");
        FlowObj x = bpd.flowObjects().get("x");
        assertTrue(x.emptyImplementation());
        assertEquals(0, x.loopType());
        assertFalse(x.multiInstance());
    }

    @Test
    void nodesAttributeDefsAndDefaults() {
        var bpd = one("<flowObject id=\"a1\" componentType=\"Service\"><isHidden>true</isHidden></flowObject>",
                MAIN_BPMN);
        Node g = bpd.node("g");
        assertEquals("exclusiveGateway", g.kind());
        assertEquals("f3", g.defaultFlowId());
        assertTrue(g.isGateway());
        Node a1 = bpd.node("a1");
        assertTrue(a1.isActivity());
        assertEquals("ServiceTask", a1.activityType());
        assertTrue(a1.isSystemActivity());
        assertTrue(a1.hidden()); // FlowObject hidden -> node hidden
        Node be = bpd.node("be");
        assertEquals("a1", be.attachedToRef());
        assertTrue(be.hasErrorDef());
        assertEquals("err1", be.defs().get(0).eventImplId());
        // default f3 at gate: conditional f2 stays a normal edge, unconditional f3 becomes the default
        assertTrue(bpd.flows().stream().filter(f -> f.id().equals("f3")).findFirst().orElseThrow().isDefault());
        assertFalse(bpd.flows().stream().filter(f -> f.id().equals("f2")).findFirst().orElseThrow().isDefault());
    }

    @Test
    void humanRealTaskAndDefaultFromExtensionElement() {
        var bpd = one("", """
                <userTask id="u" twl:isHumanTask="true">
                  <extensionElements><twl:default>fz</twl:default></extensionElements>
                </userTask>
                <sequenceFlow id="fz" sourceRef="u" targetRef="u"/>
                """);
        Node u = bpd.node("u");
        assertTrue(u.isHumanTask());
        assertFalse(u.isSystemActivity());
        assertEquals("fz", u.defaultFlowId());
    }

    @Test
    void defaultElementWithWhiteContentEmptyFromExtension() {
        var bpd = one("", """
                <exclusiveGateway id="g">
                  <extensionElements><twl:default>   </twl:default></extensionElements>
                </exclusiveGateway>
                """);
        assertNull(bpd.node("g").defaultFlowId());
    }

    @Test
    void edgesConditionsAndScriptFallback() {
        var bpd = one("", """
                <exclusiveGateway id="g"/>
                <sequenceFlow id="f1" sourceRef="g" targetRef="g">
                  <conditionScript>tw.local.a</conditionScript>
                  <conditionExpression>tw.local.b</conditionExpression>
                </sequenceFlow>
                <sequenceFlow id="f2" sourceRef="g" targetRef="g">
                  <conditionExpression>   </conditionExpression>
                </sequenceFlow>
                """);
        assertTrue(bpd.flows().get(0).conditional());
        assertEquals("tw.local.b", bpd.flows().get(0).condition());
        assertFalse(bpd.flows().get(1).conditional());
    }

    @Test
    void lanesWithinAndOutsideOfProcess() {
        var inside = one("", """
                <lane id="l1" name="Sachb." isSystemLane="false" partitionElementRef="part1">
                  <flowNodeRef>s</flowNodeRef><flowNodeRef> g </flowNodeRef>
                </lane>
                <startEvent id="s"/>
                """);
        assertEquals(1, inside.lanes().size());
        assertEquals("part1", inside.lanes().get("l1").participantRef());
        assertEquals(List.of("s", "g"), inside.lanes().get("l1").flowNodeRefs());
        assertEquals("l1", inside.node("s").laneId());
        // "g" is only referenced, not exported as a node -> no laneId assignment possible

        var outside = BpdModels.of(result(List.of(obj(BPD_ID, "B", "twBPD",
                "<twObject><bpd>" + embed("<definitions><lane id=\"lx\" name=\"Aussen\">"
                        + "<partitionElementRef>pp</partitionElementRef><flowNodeRef>s</flowNodeRef></lane>"
                        + "<process><startEvent id=\"s\"/></process></definitions>")
                        + "</bpd></twObject>")))).get(0);
        assertEquals("pp", outside.lanes().get("lx").participantRef());
        assertEquals("lx", outside.node("s").laneId());
    }

    @Test
    void systemLaneDetection() {
        var bpd = one("", """
                <lane id="ls" isSystemLane="true"><flowNodeRef>s</flowNodeRef></lane>
                <startEvent id="s"/>
                """);
        assertTrue(bpd.inSystemLane(bpd.node("s")));
        assertEquals("ls", bpd.laneOf(bpd.node("s")).id());
    }

    @Test
    void bpmn2DataMissingOrBrokenOnlyFlowObjects() {
        var bpd = new Bpd("x", "N", List.of(), List.of(), java.util.Map.of(), java.util.Map.of(), false, 0).finish();
        assertTrue(bpd.nodes().isEmpty());
        var leer = BpdModels.of(result(List.of(obj(BPD_ID, "B", "twBPD",
                "<twObject><bpd><flowObject id=\"a\" componentType=\"S\"/></bpd></twObject>"))));
        assertEquals(1, leer.size());
        assertTrue(leer.get(0).nodes().isEmpty());
        assertEquals(1, leer.get(0).flowObjects().size());
    }

    // ---------- Queries ----------

    @Test
    void successorsReachabilityAlive() {
        var bpd = one("", MAIN_BPMN);
        assertEquals(List.of("e", "be"), bpd.successors("a1")); // sequence flow + boundary
        assertEquals(java.util.Set.of("g", "a1", "e", "be"), bpd.reachableFrom("s"));
        assertTrue(bpd.reaches("s", "be"));
        assertFalse(bpd.reaches("e", "s"));
        assertTrue(bpd.liveNodes().contains("s"));
        assertFalse(bpd.liveNodes().contains("tot"));
    }

    @Test
    void unconditionalCycleOnlyWithoutConditionsAndDefaults() {
        String bedingt = """
                <startEvent id="s"/>
                <callActivity id="a"/><callActivity id="b"/>
                <sequenceFlow id="f1" sourceRef="s" targetRef="a"/>
                <sequenceFlow id="f2" sourceRef="a" targetRef="b"><conditionExpression>x</conditionExpression></sequenceFlow>
                <sequenceFlow id="f3" sourceRef="b" targetRef="a"/>
                """;
        assertNull(one("", bedingt).unconditionalCycle());

        String unbedingt = bedingt.replace("><conditionExpression>x</conditionExpression", "");
        var cyc = one("", unbedingt).unconditionalCycle();
        assertNotNull(cyc);
        assertTrue(cyc.containsAll(List.of("a", "b")));

        String selbst = """
                <callActivity id="a"/>
                <sequenceFlow id="fs" sourceRef="a" targetRef="a"/>
                """;
        assertNull(one("", selbst).unconditionalCycle());
    }

    @Test
    void longestActivityChainAndBranches() {
        var bpd = one("", """
                <startEvent id="s"/>
                <callActivity id="a1"/><callActivity id="a2"/><callActivity id="a3"/>
                <exclusiveGateway id="g"/>
                <endEvent id="e"/>
                <sequenceFlow id="f1" sourceRef="s" targetRef="a1"/>
                <sequenceFlow id="f2" sourceRef="a1" targetRef="a2"/>
                <sequenceFlow id="f3" sourceRef="a2" targetRef="g"/>
                <sequenceFlow id="f4" sourceRef="g" targetRef="a3"/>
                <sequenceFlow id="f5" sourceRef="a3" targetRef="e"/>
                """);
        assertEquals(3, bpd.longestActivityChain()); // a1, a2, a3 — gate does not separate
    }

    @Test
    void nodesWithoutContributionEmptyChainWithoutStartEvent() {
        var bpd = one("", "<callActivity id=\"a\"/>");
        assertEquals(0, bpd.longestActivityChain());
        assertEquals(0, bpd.incoming("a").size());
        assertEquals(0, bpd.outgoing("a").size());
    }

    @Test
    void activityTypesAndEvents() {
        var bpd = one("", """
                <startEvent id="s"/>
                <scriptTask id="st"/>
                <subProcess id="sp"/>
                <intermediateCatchEvent id="ce"><timerEventDefinition/></intermediateCatchEvent>
                """);
        assertTrue(bpd.node("st").isActivity());
        assertTrue(bpd.node("st").isSystemActivity());
        assertFalse(bpd.node("sp").isActivity());
        assertTrue(bpd.node("ce").isEvent());
        assertTrue(bpd.node("ce").hasDef("timerEventDefinition"));
        assertFalse(bpd.node("ce").hasErrorDef());
        assertNull(bpd.node("unbekannt"));
    }
}

package io.twxav2.analysis.ref.parse;

import io.twxav2.analysis.ref.model.BpdModel;
import io.twxav2.analysis.ref.model.TwxObject;
import io.twxav2.analysis.ref.model.TwxPackage;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * BPD parser (prefix 25): parameters/variables from bpdParameter, privateVariable
 * and the BPMN2 dataObjects in the entity-escaped bpmn2Data block; lanes with
 * flow objects, gateways, events, mappings and flow wiring.
 */
class BpdParserTest {

    private static TwxObject bpdObject(String xml) {
        TwxPackage pkg = new TwxPackage();
        pkg.name = "Demo"; pkg.acronym = "DEMO";
        TwxObject o = new TwxObject("25.b1", "Prozess", "bpd", "snap-1", xml, pkg);
        pkg.objects.put(o.id, o);
        return o;
    }

    private static BpdModel parse(String xml) {
        return BpdParser.parse(bpdObject(xml));
    }

    // ---------- Header data ----------

    @Test
    void headAndSwitch() {
        BpdModel b = parse("""
                <bpd><documentation>Prozessdoku</documentation>
                <isTrackingEnabled>true</isTrackingEnabled>
                <isCriticalPathEnabled>true</isCriticalPathEnabled>
                <isSpcEnabled>false</isSpcEnabled>
                <participantRef>/00.team</participantRef>
                <ownerTeamParticipantRef>/00.owner</ownerTeamParticipantRef>
                <businessDataParticipantRef>/00.bd</businessDataParticipantRef>
                </bpd>""");
        assertEquals("25.b1", b.id);
        assertEquals("Prozess", b.name);
        assertEquals("Prozessdoku", b.documentation);
        assertTrue(b.trackingEnabled);
        assertTrue(b.criticalPathEnabled);
        assertFalse(b.spcEnabled);
        assertEquals("/00.team", b.participantRef);
        assertEquals("/00.owner", b.ownerTeamRef);
        assertEquals("/00.bd", b.businessDataParticipantRef);
        assertFalse(b.bpmn2);
    }

    @Test
    void bpmn2DataOnlyWithoutIsNull() {
        assertFalse(parse("<bpd><bpmn2Data isNull=\"true\"></bpmn2Data></bpd>").bpmn2);
        assertTrue(parse("<bpd><bpmn2Data>&lt;data/&gt;</bpmn2Data></bpd>").bpmn2);
    }

    // ---------- Parameters and variables ----------

    @Test
    void bpdParameterAfterDirectionSorts() {
        BpdModel b = parse("""
                <bpd>
                  <bpdParameter name="in1"><bpdParameterId>p-in</bpdParameterId>
                    <parameterType>1</parameterType><classId>/12.str</classId>
                    <isArrayOf>true</isArrayOf><hasDefault>true</hasDefault>
                    <defaultValue>x</defaultValue></bpdParameter>
                  <bpdParameter name="out1"><bpdParameterId>p-out</bpdParameterId>
                    <parameterType>2</parameterType></bpdParameter>
                  <bpdParameter name="pv"><bpdParameterId>p-priv</bpdParameterId>
                    <parameterType>3</parameterType></bpdParameter>
                  <bpdParameter name="los"><bpdParameterId>p-los</bpdParameterId>
                    <parameterType>9</parameterType></bpdParameter>
                </bpd>""");
        assertEquals(2, b.parameters.size());  // input/output only
        assertEquals(2, b.variables.size());   // private + parameter
        var in = b.parameters.get(0);
        assertEquals("in1", in.name);
        assertEquals("p-in", in.id);
        assertEquals("/12.str", in.classRef);
        assertTrue(in.isList);
        assertTrue(in.input);
        assertFalse(in.output);
        assertEquals("input", in.kind);
        assertTrue(in.hasDefault);
        assertEquals("x", in.defaultValue);
        assertEquals("output", b.parameters.get(1).kind);
        assertEquals("private", b.variables.get(0).kind);
        assertEquals("parameter", b.variables.get(1).kind);
    }

    @Test
    void searchFieldsAndCorrelator() {
        BpdModel b = parse("""
                <bpd>
                  <bpdParameter name="a"><parameterType>3</parameterType>
                    <isProcessInstanceCorrelator>true</isProcessInstanceCorrelator></bpdParameter>
                  <bpdParameter name="b"><parameterType>3</parameterType>
                    <isSearchable>true</isSearchable></bpdParameter>
                </bpd>""");
        assertEquals(2, b.searchableFields);
    }

    @Test
    void privateVariableOnlyWhenStillNameless() {
        BpdModel b = parse("""
                <bpd>
                  <bpdParameter name="dup"><parameterType>1</parameterType></bpdParameter>
                  <privateVariable id="pv1"><name>dup</name><classId>/12.x</classId></privateVariable>
                  <privateVariable id="pv2"><name>neu</name><classId>/12.y</classId>
                    <arrayOf>true</arrayOf><hasDefault>true</hasDefault>
                    <defaultValue>3</defaultValue></privateVariable>
                  <privateVariable id="pv3"><name></name></privateVariable>
                </bpd>""");
        assertEquals(1, b.variables.size());   // dup already a parameter, pv3 nameless
        var neu = b.variables.get(0);
        assertEquals("neu", neu.name);
        assertEquals("pv2", neu.id);
        assertEquals("private", neu.kind);
        assertTrue(neu.isList);
        assertTrue(neu.hasDefault);
        assertEquals("3", neu.defaultValue);
    }

    @Test
    void dataObjectsFromTheEscapedBpmn2Data() {
        BpdModel b = parse("""
                <bpd><bpdParameter name="dup"><parameterType>1</parameterType></bpdParameter>
                <bpmn2Data>&lt;bpmn2:process&gt;&lt;bpmn2:dataObject id="do1" name="dup" /&gt;"""
                + """
                &lt;dataObject id="do2" name="neu" isCollection="true" itemSubjectRef="itm.12.abc"/&gt;"""
                + """
                &lt;dataObject id="do3" name="ohneItem" itemSubjectRef="plain"/&gt;"""
                + """
                &lt;dataObject id="do4"/&gt;&lt;/bpmn2:process&gt;</bpmn2Data></bpd>""");
        assertEquals(2, b.variables.size());   // dup deduplicated, do4 nameless
        var neu = b.variables.get(0);
        assertEquals("neu", neu.name);
        assertEquals("do2", neu.id);
        assertTrue(neu.isList);
        assertEquals("12.abc", neu.classRef);  // itm. prefix is cut off
        assertEquals("plain", b.variables.get(1).classRef);
    }

    @Test
    void bpmn2DataWithIsNullIsIgnored() {
        // real TWX form: isNull attribute and empty content
        BpdModel b = parse("<bpd><bpmn2Data isNull=\"true\"></bpmn2Data></bpd>");
        assertTrue(b.variables.isEmpty());
        assertFalse(b.bpmn2);   // flag recognizes the isNull form in the raw text
    }

    // ---------- Lanes and flow objects ----------

    private static final String LANE = """
            <bpd><lane id="l1" name="Sachbearbeitung">
              <name>Sachbearbeitung</name><!-- echte TWX-Lanes tragen den Namen als erstes Kind -->
              <attachedParticipant>/00.team</attachedParticipant><height>400</height>
              <systemLane>true</systemLane>
              <flowObject id="a1" componentType="Service">
                <location x="-5" y="7"/>
                <name>Aktivitaet</name><documentation>doku</documentation>
                <narrative>text</narrative><subject>betreff</subject>
                <script>tw.local.a = 1;</script>
                <loopType>2</loopType><implementationType>Java</implementationType>
                <bpmnTaskType>UserTask</bpmnTaskType>
                <isConditional>true</isConditional><isHidden>true</isHidden>
                <isRepeatable>true</isRepeatable><isAutoflowable>true</isAutoflowable>
                <cleanupTask>true</cleanupTask><sendToType>1</sendToType>
                <isErrorHandlerEnabled>true</isErrorHandlerEnabled>
                <inputActivityParameterMapping><name>p</name><value>f()</value></inputActivityParameterMapping>
                <outputActivityParameterMapping><name>o</name><value>v + 1</value></outputActivityParameterMapping>
              </flowObject>
              <flowObject id="gw1" componentType="Gateway"><gatewayType>XOR</gatewayType></flowObject>
              <flowObject id="ev1" componentType="Event">
                <eventType>timer</eventType><eventActionType>2</eventActionType>
                <attachedActivityId>a1</attachedActivityId>
                <errorEvent>x</errorEvent>
              </flowObject>
            </lane></bpd>""";

    @Test
    void laneWithFlowObjects() {
        BpdModel b = parse(LANE);
        assertEquals(1, b.lanes.size());
        var lane = b.lanes.get(0);
        assertEquals("l1", lane.id);
        // real TWX: lane carries <name> as first child before the flow objects
        assertEquals("Sachbearbeitung", lane.name);
        assertEquals("/00.team", lane.participantRef);
        assertEquals(400, lane.height);
        assertTrue(lane.system);
        assertEquals(3, lane.flowObjects.size());
        assertEquals(3, b.flowObjects.size());

        var a = b.flowObject("a1");
        assertEquals(-5, a.x);
        assertEquals(7, a.y);
        assertEquals("Aktivitaet", a.name);
        assertEquals("Service", a.componentType);
        assertEquals("l1", a.laneId);
        assertEquals("Sachbearbeitung", a.laneName);
        assertTrue(a.systemLane);
        assertEquals("doku", a.documentation);
        assertEquals("text", a.narrative);
        assertEquals("betreff", a.subject);
        assertEquals("tw.local.a = 1;", a.script);
        assertEquals(2, a.loopType);
        assertTrue(a.multiInstance);
        assertEquals("Java", a.implementationType);
        assertEquals("UserTask", a.taskType);
        assertTrue(a.conditional);
        assertTrue(a.hidden);
        assertTrue(a.repeatable);
        assertTrue(a.autoflow);
        assertTrue(a.deleteTaskOnCompletion);   // via cleanupTask
        assertEquals("1", a.assignmentType);
        assertTrue(a.hasErrorHandling);         // via isErrorHandlerEnabled in the activity block
        assertEquals(2, a.mappings.size());
        assertTrue(a.mappings.get(0).input);
        assertEquals("p", a.mappings.get(0).parameterName);
        assertEquals("f()", a.mappings.get(0).value);
        assertFalse(a.mappings.get(1).input);

        assertEquals("XOR", b.flowObject("gw1").gatewayType);
        var ev = b.flowObject("ev1");
        assertEquals("timer", ev.eventType);
        assertEquals("2", ev.eventAction);
        assertEquals("a1", ev.attachedRef);
        assertTrue(ev.isTimerEvent());
    }

    @Test
    void attachedBoundaryEvents() {
        BpdModel b = parse("""
                <bpd><lane id="l"><flowObject id="a"><name>A</name>
                  <attachedEvent id="e1">
                    <location x="1" y="2"/><name>Fehler</name>
                    <eventActionType>4</eventActionType>
                    <script>tw.local.e = 1;</script><ucaId>/11.uca</ucaId>
                    <cancelActivity>false</cancelActivity>
                  </attachedEvent>
                  <attachedEvent id="e2"><name>Timer</name><cancelActivity>true</cancelActivity></attachedEvent>
                </flowObject></lane></bpd>""");
        assertEquals(3, b.flowObjects.size());
        var e1 = b.flowObject("e1");
        assertEquals("Event", e1.componentType);
        assertEquals("a", e1.attachedToId);
        assertEquals("l", e1.laneId);
        assertEquals(1, e1.x);
        assertEquals("tw.local.e = 1;", e1.script);
        assertEquals("/11.uca", e1.ucaRef);
        assertFalse(e1.interrupting);      // cancelActivity=false
        assertTrue(e1.isErrorEvent());     // eventActionType 4
        assertTrue(b.flowObject("a").hasErrorHandling);
        assertTrue(b.flowObject("e2").interrupting);
        assertFalse(b.flowObject("e2").isErrorEvent());
    }

    @Test
    void attachedEventsWithoutLocation() {
        BpdModel b = parse("<bpd><lane id=\"l\"><flowObject id=\"a\"><x/>"
                + "<attachedEvent id=\"e\"><name>N</name></attachedEvent></flowObject></lane></bpd>");
        assertEquals(0, b.flowObject("e").x);
        assertEquals(0, b.flowObject("e").y);
    }

    @Test
    void attachedRefCascadesOverProcessRefVariants() {
        BpdModel b = parse("""
                <bpd><lane id="l">
                  <flowObject id="x1"><attachedProcessRef>/25.ref1</attachedProcessRef></flowObject>
                  <flowObject id="x2"><attachedProcessId>/25.ref2</attachedProcessId></flowObject>
                </lane></bpd>""");
        assertEquals("/25.ref1", b.flowObject("x1").attachedRef);
        assertEquals("/25.ref2", b.flowObject("x2").attachedRef);
    }

    // ---------- Flows ----------

    @Test
    void flowsWithEndpointsAndConditions() {
        BpdModel b = parse("""
                <bpd><lane id="l">
                  <flowObject id="a"><outputPort><flow ref="f1"/></outputPort></flowObject>
                  <flowObject id="b"><inputPort><flow ref="f1"/></inputPort>
                    <outputPort><flow ref="f2"/><flow ref="unbekannt"/></outputPort></flowObject>
                </lane>
                <flow id="f1" connectionType="normal"><name>Weiter</name>
                  <expression>tw.local.x &gt; 1</expression></flow>
                <flow id="f2"><isDefaultFlow>true</isDefaultFlow></flow>
                <flow ref="f1"/>
                </bpd>""");
        assertEquals(2, b.flows.size());   // <flow ref=.../> from the ports is not a flow
        var f1 = b.flows.get(0);
        assertEquals("f1", f1.id);
        assertEquals("normal", f1.connectionType);
        assertEquals("Weiter", f1.name);
        assertEquals("tw.local.x > 1", f1.condition);
        assertEquals("a", f1.fromId);
        assertEquals("b", f1.toId);
        assertFalse(f1.isDefault);
        assertTrue(b.flows.get(1).isDefault);
        assertEquals("b", b.flows.get(1).fromId);   // from b's outputPort
        assertEquals("", b.flows.get(1).toId);      // "unbekannt" leads nowhere
    }

    // ---------- Small utilities ----------

    @Test
    void epvReferencesAndCounts() {
        BpdModel b = parse("<bpd><epvId>/52.epv1</epvId><epvId>/52.epv2</epvId></bpd>");
        assertEquals(java.util.List.of("/52.epv1", "/52.epv2"), b.epvRefs);
        assertEquals(3, BpdParser.count("aXbXcX", "X"));
        assertEquals(0, BpdParser.count("abc", "X"));
        assertEquals(1, BpdParser.count("aaa", "aa"));   // non-overlapping: 1 + rest "a"
    }

    @Test
    void hasVariableOverParameterAndVariable() {
        BpdModel b = parse("<bpd><bpdParameter name=\"p\"><parameterType>1</parameterType></bpdParameter>"
                + "<privateVariable id=\"v\"><name>q</name></privateVariable></bpd>");
        assertTrue(BpdParser.hasVariable(b, "p"));
        assertTrue(BpdParser.hasVariable(b, "q"));
        assertFalse(BpdParser.hasVariable(b, "nix"));
    }
}

package io.twxav2.analysis.diagram;

import io.twxav2.analysis.diagram.Diagram.Edge;
import io.twxav2.analysis.diagram.Diagram.Lane;
import io.twxav2.analysis.diagram.Diagram.Model;
import io.twxav2.analysis.diagram.Diagram.Node;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Diagram geometry (B5): nodes/edges from the model JSON, swim lanes from
 * the BPMN XML, lane dedup, entity encoding — synthetic models.
 */
class DiagramTest {

    private static final String VIS = "\"nodeVisualInfo\": { \"x\": 10, \"y\": 20, \"width\": 50, \"height\": 40 }";

    private static String model(String valuesJson) {
        return "<twObject><jsonData>{ \"values\": [" + valuesJson + "] }</jsonData></twObject>";
    }

    // ---------- Nodes ----------

    @Test
    void nullAndWithoutJsonDataAndBrokenJsonReturnEmptyOrOnlyLanes() {
        assertTrue(Diagram.of(null).isEmpty());
        assertTrue(Diagram.of("<twObject/>").isEmpty());
        Model kaputt = Diagram.of("<twObject><jsonData>{ kaputt</jsonData>"
                + "<bpmn2:lane id=\"l\" isSystemLane=\"false\">"
                + "<bpmn2:nodeVisualInfo x=\"0\" y=\"0\" width=\"100\" height=\"60\"/>"
                + "</bpmn2:lane></twObject>");
        assertTrue(kaputt.nodes().isEmpty());
        assertEquals(1, kaputt.lanes().size()); // lanes survive even with broken model JSON
    }

    @Test
    void nodesNameFromOwnFieldOrParentName() {
        Model m = Diagram.of(model(
                "{ \"id\": \"e1\", \"declaredType\": \"startEvent\", \"name\": \"Start\","
                        + " \"extensionElements\": { " + VIS + " } },"
                        + "{ \"name\": \"Vater\", \"kinder\": ["
                        + "{ \"id\": \"c1\", \"declaredType\": \"task\", \"extensionElements\": { " + VIS + " } } ] }"));
        assertEquals("Start", m.nodes().get(0).name());
        assertEquals("Vater", m.nodes().get(1).name()); // inherited from the outer node
    }

    @Test
    void nodesWithoutIdOrTypeOrVisualInfoNoNodes() {
        Model m = Diagram.of(model(
                "{ \"declaredType\": \"x\", \"extensionElements\": { " + VIS + " } },"
                        + "{ \"id\": \"z2\", \"extensionElements\": { " + VIS + " } },"
                        + "{ \"id\": \"z3\", \"declaredType\": \"x\", \"extensionElements\": {} }"));
        assertTrue(m.nodes().isEmpty());
        assertTrue(m.edges().isEmpty());
    }

    @Test
    void visualInfoAsArrayFirstObjectCountsStandardMeasuresWithoutColor() {
        Model m = Diagram.of(model(
                "{ \"id\": \"t1\", \"declaredType\": \"task\","
                        + " \"extensionElements\": { \"nodeVisualInfo\": ["
                        + "{ \"x\": 1, \"color\": \"\" }, {} ] } }"));
        Node n = m.nodes().get(0);
        assertEquals(1, n.x());
        assertEquals(90, n.width());   // designer default when unset
        assertEquals(60, n.height());
        assertNull(n.color());
    }

    @Test
    void visualInfoEmptyArrayOrScalarNoNodes() {
        Model m = Diagram.of(model(
                "{ \"id\": \"a\", \"declaredType\": \"t\", \"extensionElements\": { \"nodeVisualInfo\": [] } },"
                        + "{ \"id\": \"b\", \"declaredType\": \"t\", \"extensionElements\": { \"nodeVisualInfo\": 7 } }"));
        assertTrue(m.nodes().isEmpty());
    }

    @Test
    void nodesNameEmptyStringIsNull() {
        Model m = Diagram.of(model(
                "{ \"id\": \"e1\", \"declaredType\": \"t\", \"name\": \"  \","
                        + " \"extensionElements\": { " + VIS + " } }"));
        assertNull(m.nodes().get(0).name());
    }

    // ---------- Edges ----------

    @Test
    void edgeWithPortsAndCondition() {
        Model m = Diagram.of(model(
                "{ \"id\": \"f1\", \"declaredType\": \"sequenceFlow\", \"name\": \"ok\","
                        + " \"sourceRef\": \"e1\", \"targetRef\": \"e2\","
                        + " \"extensionElements\": { \"linkVisualInfo\": { \"sourcePortLocation\": \"TRBL-9\" } },"
                        + " \"conditionExpression\": { \"content\": [\"a > 1\", \"&& b\"] } }"));
        assertEquals(1, m.edges().size());
        Edge e = m.edges().get(0);
        assertEquals("e1", e.source());
        assertEquals("e2", e.target());
        assertEquals("TRBL-9", e.sourcePort());
        assertNull(e.targetPort());
        assertEquals("a > 1 && b", e.condition());
    }

    @Test
    void edgeWithoutSourceOrTargetFallsAway() {
        Model m = Diagram.of(model(
                "{ \"id\": \"f1\", \"declaredType\": \"sequenceFlow\","
                        + " \"sourceRef\": \"e1\","
                        + " \"extensionElements\": { \"linkVisualInfo\": {} } }"));
        assertTrue(m.edges().isEmpty());
    }

    @Test
    void linkVisualInfoWithoutExtensionOrSourceCodeWithoutObjectNoEdge() {
        Model m = Diagram.of(model(
                "{ \"id\": \"f\", \"declaredType\": \"sequenceFlow\", \"sourceRef\": \"a\","
                        + " \"targetRef\": \"b\", \"extensionElements\": { \"linkVisualInfo\": [] } }"));
        assertTrue(m.edges().isEmpty());
    }

    @Test
    void conditionOverConditionScriptAndEmptyAsNull() {
        Model mit = Diagram.of(model(
                "{ \"id\": \"f\", \"declaredType\": \"sequenceFlow\", \"sourceRef\": \"a\", \"targetRef\": \"b\","
                        + " \"extensionElements\": { \"linkVisualInfo\": {} },"
                        + " \"conditionScript\": { \"content\": [\"x == 1\"] } }"));
        assertEquals("x == 1", mit.edges().get(0).condition());
        Model ohne = Diagram.of(model(
                "{ \"id\": \"f\", \"declaredType\": \"sequenceFlow\", \"sourceRef\": \"a\", \"targetRef\": \"b\","
                        + " \"extensionElements\": { \"linkVisualInfo\": {} },"
                        + " \"conditionExpression\": { \"content\": [] } }"));
        assertNull(ohne.edges().get(0).condition());
    }

    @Test
    void longConditionIsTruncated() {
        String lang = "\"" + "y".repeat(200) + "\""; // a JSON string with 200 y
        Model m = Diagram.of(model(
                "{ \"id\": \"f\", \"declaredType\": \"sequenceFlow\", \"sourceRef\": \"a\", \"targetRef\": \"b\","
                        + " \"extensionElements\": { \"linkVisualInfo\": {} },"
                        + " \"conditionExpression\": { \"content\": [" + lang + "] } }"));
        String cond = m.edges().get(0).condition();
        assertTrue(cond.endsWith("..."));
        assertEquals(143, cond.length()); // 140 + "..."
    }

    // ---------- Lanes ----------

    private static final String LANE_XML = """
            <bpmn2:lane id="l1" name="Sachbearbeit" isSystemLane="false">
              <bpmn2:nodeVisualInfo x="5" y="15" width="300" height="120" color="#eeeeee"/>
              <bpmn2:flowNodeRef>  e1  </bpmn2:flowNodeRef>
              <bpmn2:flowNodeRef>e2</bpmn2:flowNodeRef>
            </bpmn2:lane>""";

    @Test
    void laneWithGeometryColorAndReferences() {
        Model m = Diagram.of("<twObject>" + LANE_XML + "</twObject>");
        assertEquals(1, m.lanes().size());
        Lane l = m.lanes().get(0);
        assertEquals("l1", l.id());
        assertEquals("Sachbearbeit", l.name());
        assertEquals(300, l.width());
        assertEquals("#eeeeee", l.color());
        assertFalse(l.systemLane());
        assertEquals(java.util.List.of("e1", "e2"), l.nodeIds());
    }

    @Test
    void laneWithoutIsSystemLaneAttribWithoutGeometryOrSizeSkipped() {
        Model m = Diagram.of("<twObject>"
                + "<lane id=\"a\" name=\"keinAttribut\"><nodeVisualInfo width=\"9\" height=\"9\"/></lane>"
                + "<lane id=\"b\" name=\"ohneVis\" isSystemLane=\"false\">text</lane>"
                + "<lane id=\"c\" name=\"nullBreite\" isSystemLane=\"false\"><nodeVisualInfo x=\"3\"/></lane>"
                + "</twObject>");
        assertTrue(m.lanes().isEmpty());
    }

    @Test
    void systemLaneFlagAndWithoutNamesWithoutId() {
        Model m = Diagram.of("<twObject>"
                + "<lane isSystemLane=\"TRUE\"><nodeVisualInfo width=\"10\" height=\"10\"/></lane>"
                + "</twObject>");
        Lane l = m.lanes().get(0);
        assertTrue(l.systemLane());
        assertNull(l.id());
        assertNull(l.name());
    }

    @Test
    void twoLanesPerOwnGeometryWithoutTrickleThrough() {
        Model m = Diagram.of("<twObject>"
                + "<lane id=\"l1\" isSystemLane=\"false\" name=\"Eins\">"
                + "<nodeVisualInfo x=\"0\" y=\"0\" width=\"100\" height=\"50\"/></lane>"
                + "<lane id=\"l2\" isSystemLane=\"true\" name=\"Zwei\">"
                + "<nodeVisualInfo x=\"0\" y=\"60\" width=\"100\" height=\"50\"/></lane>"
                + "</twObject>");
        assertEquals(2, m.lanes().size());
        assertEquals(0, m.lanes().get(0).y());
        assertEquals(60, m.lanes().get(1).y());
        assertTrue(m.lanes().get(1).systemLane());
    }

    @Test
    void laneNodesFromModelJsonAreRemoved() {
        Model m = Diagram.of("<twObject><jsonData>{ \"values\": ["
                + "{ \"id\": \"l1\", \"declaredType\": \"lane\", \"extensionElements\": { " + VIS + " } },"
                + "{ \"id\": \"k1\", \"declaredType\": \"task\", \"extensionElements\": { " + VIS + " } }"
                + "] }</jsonData>"
                + "<lane id=\"l1\" isSystemLane=\"false\"><nodeVisualInfo width=\"10\" height=\"10\"/></lane>"
                + "</twObject>");
        assertEquals(java.util.List.of("k1"), m.nodes().stream().map(Node::id).toList());
        assertEquals(1, m.lanes().size());
    }

    // ---------- Entity encoding ----------

    @Test
    void entityEncodedModelAndXmlAreUnpacked() {
        String model = ("{ \"values\": [ { \"id\": \"e1\", \"declaredType\": \"task\", \"name\": \"A&amp;B\","
                + " \"extensionElements\": { " + VIS + " } } ] }").replace("\"", "&quot;");
        String raw = "<twObject><jsonData>" + model + "</jsonData>"
                + "&lt;lane id=&quot;l1&quot; name=&quot;Bahn&quot; isSystemLane=&quot;false&quot;&gt;"
                + "&lt;nodeVisualInfo width=&quot;10&quot; height=&quot;10&quot;/&gt;&lt;/lane&gt;"
                + "</twObject>";
        Model m = Diagram.of(raw);
        assertEquals("A&B", m.nodes().get(0).name());     // entity inside a JSON value unescaped
        assertEquals("Bahn", m.lanes().get(0).name());    // lane from encoded XML
    }

    @Test
    void unescNamedNumericAndOutsideRangeEntities() {
        assertEquals("\"'<&>", Diagram.unescape("&quot;&apos;&lt;&amp;&gt;"));
        assertEquals("a\r\nb", Diagram.unescape("a&#13;&#10;b"));
        assertEquals("ü", Diagram.unescape("&#252;"));
        assertEquals("&#99999999999;", Diagram.unescape("&#99999999999;"));
        assertEquals("nichts", Diagram.unescape("nichts"));
    }
}

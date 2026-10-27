package io.twxav2.analysis.details;

import io.twxav2.analysis.details.ObjectDetails.Section;
import org.junit.jupiter.api.Test;

import java.util.List;

import static io.twxav2.analysis.ModelsFixtures.obj;
import static io.twxav2.analysis.ModelsFixtures.uuid;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Detail tables (B7) per object type: service, BPD, business data type, coach
 * view, team and script blocks — synthetic single objects.
 */
class ObjectDetailsTest {

    private static Section byTitle(List<Section> sections, String title) {
        return sections.stream().filter(s -> s.title().equals(title)).findFirst().orElseThrow();
    }

    // ---------- Base behavior ----------

    @Test
    void withoutContentAndForeignPrefixNoSections() {
        assertTrue(ObjectDetails.sections(obj("1." + uuid('a'), "S", "twProcess", null)).isEmpty());
        assertTrue(ObjectDetails.sections(
                obj("99." + uuid('b'), "X", "twX", "<twObject>text</twObject>")).isEmpty());
    }

    @Test
    void unreadableXmlThrowsNot() {
        var secs = ObjectDetails.sections(obj("1." + uuid('c'), "S", "twProcess", "kein xml <<"));
        assertTrue(secs.isEmpty());
    }

    // ---------- Process/service (prefix 1) ----------

    @Test
    void serviceWithoutProcessElementNoSections() {
        assertTrue(ObjectDetails.sections(
                obj("1." + uuid('d'), "S", "twProcess", "<twObject><anderes/></twObject>")).isEmpty());
    }

    @Test
    void serviceDeclarationsStepsTransitionsAndOverview() {
        var o = obj("1." + uuid('e'), "Svc", "twProcess", """
                <twObject><process>
                  <processType>2</processType>
                  <processVariable name="start"><hasDefault>true</hasDefault></processVariable>
                  <processVariable name="ende"><hasDefault>false</hasDefault></processVariable>
                  <item><name>S1</name><tWComponentName>SvcA</tWComponentName><processPrePosts/></item>
                  <item><name>S2</name></item>
                  <link name="ok"/>
                  <link/>
                </process></twObject>""");
        var secs = ObjectDetails.sections(o);
        var vars = byTitle(secs, "Declarations (tw.local)");
        assertEquals(List.of("start", "yes"), vars.rows().get(0));
        assertEquals(List.of("ende", ""), vars.rows().get(1));
        var steps = byTitle(secs, "Steps");
        assertEquals(List.of("S1", "SvcA"), steps.rows().get(0));
        assertEquals(List.of("S2", ""), steps.rows().get(1));
        assertEquals(List.of(List.of("ok"), List.of("")), byTitle(secs, "Transitions").rows());
        var overview = byTitle(secs, "Overview").rows();
        assertEquals(List.of("Process type", "2"), overview.get(0));
        assertEquals(List.of("Pre/post scripts", "1"), overview.get(1));
        assertEquals(List.of("Declarations", "2"), overview.get(2));
        assertEquals(List.of("Steps", "2"), overview.get(3));
    }

    @Test
    void serviceOnlyOverviewWhenListsEmpty() {
        var o = obj("1." + uuid('f'), "Svc", "twProcess", "<twObject><process/></twObject>");
        var secs = ObjectDetails.sections(o);
        assertEquals(1, secs.size());
        assertEquals("Overview", secs.get(0).title());
        assertEquals(List.of("Declarations", "0"), secs.get(0).rows().get(2));
    }

    @Test
    void serviceAlignOnTheMoreAsTwoHundredDeclarations() {
        StringBuilder sb = new StringBuilder("<twObject><process>");
        for (int i = 0; i < 210; i++) {
            sb.append("<processVariable name=\"v").append(i).append("\"/>");
        }
        sb.append("</process></twObject>");
        var secs = ObjectDetails.sections(obj("1." + uuid('g'), "S", "twProcess", sb.toString()));
        assertEquals(200, byTitle(secs, "Declarations (tw.local)").rows().size());
        assertEquals(List.of("Declarations", "210"), byTitle(secs, "Overview").rows().get(2));
    }

    // ---------- BPD (prefix 25) ----------

    @Test
    void bpdWithoutBpdElementNoSections() {
        assertTrue(ObjectDetails.sections(
                obj("25." + uuid('h'), "B", "twBPD", "<twObject><x/></twObject>")).isEmpty());
    }

    @Test
    void bpdOverviewAndFlowObjectTables() {
        var o = obj("25." + uuid('i'), "B", "twBPD", """
                <twObject><bpd>
                  <autoTrackingEnabled>True</autoTrackingEnabled>
                  <deep><searchableField/></deep>
                  <flowObject name="Aktivitaet" componentType="Service"><loopType>2</loopType></flowObject>
                  <flowObject componentType="View"><name>Aus Name-Kind</name><isHidden>TRUE</isHidden></flowObject>
                  <flowObject id="d3"/>
                </bpd></twObject>""");
        var secs = ObjectDetails.sections(o);
        var overview = byTitle(secs, "Overview").rows();
        assertEquals(List.of("Flow objects", "3"), overview.get(0));
        assertEquals(List.of("Searchable Fields", "1"), overview.get(1));
        assertEquals(List.of("Auto-Tracking", "on"), overview.get(2));
        var rows = byTitle(secs, "Flow objects").rows();
        assertEquals(List.of("Aktivitaet", "Service", "Multi-instance", ""), rows.get(0));
        assertEquals(List.of("Aus Name-Kind", "View", "", "yes"), rows.get(1));
        assertEquals(List.of("", "", "", ""), rows.get(2));
    }

    // ---------- Business data type (prefix 12) ----------

    private static String classContent(String json) {
        return "<twClass><jsonData>" + json + "</jsonData></twClass>";
    }

    @Test
    void classWithoutOrWithBrokenJsonNoSections() {
        assertTrue(ObjectDetails.sections(
                obj("12." + uuid('j'), "K", "twClass", "<twClass/>")).isEmpty());
        assertTrue(ObjectDetails.sections(
                obj("12." + uuid('k'), "K", "twClass", classContent("{ kaputt"))).isEmpty());
    }

    @Test
    void classTypeKindAndAttributeFromTheJson() {
        var o = obj("12." + uuid('l'), "K", "twClass", classContent("""
                { "targetNamespace": "http://x/y",
                  "complexType": { "sequence": { "element": [
                      { "name": "strasse", "type": "string",
                        "annotation": { "documentation": [{ "content": ["Doku vorhanden"] }] } },
                      { "name": "ohneTyp" },
                      { "name": "zahl", "type": "int" } ] } } }"""));
        var secs = ObjectDetails.sections(o);
        var art = byTitle(secs, "Type summary").rows();
        assertEquals(List.of("Kind", "Hand-authored"), art.get(0));
        assertEquals(List.of("Root documentation", "none"), art.get(1));
        assertEquals(List.of("Attributes (flattened)", "2"), art.get(2));
        var attrs = byTitle(secs, "Attributes").rows();
        assertEquals(List.of("strasse", "string", "yes"), attrs.get(0));
        assertEquals(List.of("zahl", "int", ""), attrs.get(1)); // ohneTyp has no type field
    }

    @Test
    void classWrapperAndImportFromJsonFeatures() {
        var wrapper = ObjectDetails.sections(
                obj("12." + uuid('m'), "K", "twClass", classContent("{ \"rootElement\": {} }")));
        assertEquals(List.of("Kind", "Wrapper (message)"), byTitle(wrapper, "Type summary").rows().get(0));

        var imported = ObjectDetails.sections(obj("12." + uuid('n'), "K", "twClass",
                classContent("{ \"targetNamespace\": \"http://x/ExternalService\" }")));
        assertEquals(List.of("Kind", "Import (service spec)"), byTitle(imported, "Type summary").rows().get(0));
    }

    @Test
    void classesDocsVariantsAndRootDocsPresent() {
        var o = obj("12." + uuid('o'), "K", "twClass", classContent("""
                { "annotation": { "documentation": "Text direkt" },
                  "complexType": { "sequence": { "element": [
                      { "name": "a", "type": "t", "annotation": { "documentation": { "content": [] } } },
                      { "name": "b", "type": "t", "annotation": { "documentation": [{ "content": ["  "] }] } }
                  ] } } }"""));
        var secs = ObjectDetails.sections(o);
        assertEquals(List.of("Root documentation", "present"), byTitle(secs, "Type summary").rows().get(1));
        var rows = byTitle(secs, "Attributes").rows();
        assertEquals(List.of("a", "t", ""), rows.get(0));   // empty content list -> no documentation
        assertEquals(List.of("b", "t", ""), rows.get(1));   // whitespace only -> no documentation
    }

    // ---------- Coach view (prefix 64) ----------

    @Test
    void coachOptionsWithMissingLabelAndBindings() {
        var o = obj("64." + uuid('p'), "CV", "twCoachView", """
                <twCoachView>
                  <configOption name="o1" label="Sichtbar"/>
                  <configOption name="o2" label=""/>
                  <bindingType>customBinding</bindingType>
                  <bindingType name="bt2"/>
                </twCoachView>""");
        var secs = ObjectDetails.sections(o);
        var opts = byTitle(secs, "Config options").rows();
        assertEquals(List.of("o1", "Sichtbar", ""), opts.get(0));
        assertEquals(List.of("o2", "", "missing"), opts.get(1));
        assertEquals(List.of(List.of("customBinding"), List.of("bt2")),
                byTitle(secs, "Binding types").rows());
    }

    // ---------- Team (prefix 24) ----------

    @Test
    void teamMembersFromNestedLists() {
        var o = obj("24." + uuid('q'), "T", "twTeam", """
                <twTeam><participant><jsonData>{ "users": [ { "Users": ["anna",
                    { "name": "bob" }, { "user": "c" }, { "whatever": 1 } ],
                    "UserGroups": [ { "name": "  " }, { "displayName": "Dev" } ] } ] }</jsonData>
                </participant></twTeam>""");
        var rows = byTitle(ObjectDetails.sections(o), "Members").rows();
        assertEquals(List.of("anna", "Individual user"), rows.get(0));
        assertEquals(List.of("bob", "Individual user"), rows.get(1));
        assertEquals(List.of("c", "Individual user"), rows.get(2));
        assertEquals(List.of("?", "Individual user"), rows.get(3));
        assertEquals(List.of("?", "Group"), rows.get(4));
        assertEquals(List.of("Dev", "Group"), rows.get(5));
    }

    @Test
    void teamWithoutParticipantWithoutJsonOrBrokenNoSections() {
        assertTrue(ObjectDetails.sections(
                obj("24." + uuid('r'), "T", "twTeam", "<twTeam/>")).isEmpty());
        assertTrue(ObjectDetails.sections(obj("24." + uuid('s'), "T", "twTeam",
                "<twTeam><participant><other>x</other></participant></twTeam>")).isEmpty());
        assertTrue(ObjectDetails.sections(obj("24." + uuid('t'), "T", "twTeam",
                "<twTeam><participant><jsonData>{ kaputt</jsonData></participant></twTeam>")).isEmpty());
    }

    // ---------- Script blocks ----------

    @Test
    void scriptBlocksWithLocationAndLineCount() {
        var o = obj("1." + uuid('u'), "Svc", "twProcess",
                "<twObject><script name=\"s1\">zeile1\r\nzeile2\nzeile3</script></twObject>");
        var secs = ObjectDetails.sections(o);
        var scripts = byTitle(secs, "Script blocks");
        assertEquals(1, secs.size()); // service without process -> script blocks only
        assertEquals(List.of("#", "Location", "Lines"), scripts.columns());
        assertEquals("1", scripts.rows().get(0).get(0));
        assertTrue(scripts.rows().get(0).get(1).contains("s1"));
        assertEquals("3", scripts.rows().get(0).get(2)); // CR+LF and LF counted
    }

    @Test
    void prefix61WithJsFileNamesReturnsScriptBlocks() {
        var js = "tw.local.x = 1;\n// ende";
        var o = obj("61." + uuid('v'), "skript.js", "twJavaScript", js);
        var secs = ObjectDetails.sections(o);
        assertEquals(1, secs.size());
        assertEquals("2", byTitle(secs, "Script blocks").rows().get(0).get(2));
    }

    // ---------- Caps and deep search ----------

    @Test
    void flowObjectsCappedAtTwoHundredAndAutoTrackingDeep() {
        StringBuilder bpd = new StringBuilder("<twObject><bpd>");
        bpd.append("<deep><autoTrackingEnabled>True</autoTrackingEnabled></deep>");
        for (int i = 0; i < 205; i++) {
            bpd.append("<flowObject name=\"F").append(i).append("\"/>");
        }
        bpd.append("</bpd></twObject>");
        var secs = ObjectDetails.sections(obj("25." + uuid('w'), "B", "twBPD", bpd.toString()));
        assertEquals(200, byTitle(secs, "Flow objects").rows().size());
        assertEquals(List.of("Flow objects", "200"), byTitle(secs, "Overview").rows().get(0));
        assertEquals(List.of("Auto-Tracking", "on"), byTitle(secs, "Overview").rows().get(2));
    }

    @Test
    void depthRecursionEightLevelsBounded() {
        StringBuilder deep = new StringBuilder("<twObject><bpd>");
        for (int i = 0; i < 12; i++) {
            deep.append("<w").append(i).append('>');
        }
        deep.append("<autoTrackingEnabled>True</autoTrackingEnabled>");
        for (int i = 11; i >= 0; i--) {
            deep.append("</w").append(i).append('>');
        }
        deep.append("</bpd></twObject>");
        var secs = ObjectDetails.sections(obj("25." + uuid('x'), "B", "twBPD", deep.toString()));
        // match lies deeper than level 8 -> not found -> auto-tracking off
        assertEquals(List.of("Auto-Tracking", "off"), byTitle(secs, "Overview").rows().get(2));
    }

    @Test
    void teamMembersCappedAtTwoHundred() {
        StringBuilder users = new StringBuilder();
        for (int i = 0; i < 205; i++) {
            users.append(i > 0 ? "," : "").append("{\"name\":\"user").append(i).append("\"}");
        }
        String json = "{\"Users\":[" + users + "]}";
        var o = obj("24." + uuid('y'), "T", "twTeam",
                "<twObject><participant><jsonData>" + json + "</jsonData></participant></twObject>");
        var secs = ObjectDetails.sections(o);
        assertEquals(200, byTitle(secs, "Members").rows().size());
    }

    @Test
    void teamWithoutParticipantJsonNoMembers() {
        // modelJson yields (spurious) jsonData, but participant has none -> return
        var o = obj("24." + uuid('z'), "T", "twTeam",
                "<twObject><jsonData>{&quot;a&quot;:1}</jsonData><participant/></twObject>");
        assertTrue(ObjectDetails.sections(o).isEmpty());
    }

    @Test
    void classPropertyListCappedAtTwoHundred() {
        StringBuilder els = new StringBuilder();
        for (int i = 0; i < 205; i++) {
            els.append(i > 0 ? "," : "").append("{\"name\":\"f").append(i).append("\",\"type\":\"string\"}");
        }
        var o = obj("12." + uuid('A'), "K", "twClass", classContent(
                "{\"complexType\":{\"sequence\":{\"element\":[" + els + "]}}}"));
        var props = ObjectDetails.sections(o).stream()
                .filter(s -> !s.rows().isEmpty() && s.rows().get(0).get(0).equals("f0"))
                .findFirst().orElseThrow();
        assertEquals(200, props.rows().size());
    }

    @Test
    void scriptBlocksCappedAtTwoHundred() {
        StringBuilder sb = new StringBuilder("<twObject>");
        for (int i = 0; i < 205; i++) {
            sb.append("<script name=\"s").append(i).append("\">x</script>");
        }
        sb.append("</twObject>");
        var secs = ObjectDetails.sections(obj("1." + uuid('B'), "S", "twProcess", sb.toString()));
        assertEquals(200, byTitle(secs, "Script blocks").rows().size());
    }
}

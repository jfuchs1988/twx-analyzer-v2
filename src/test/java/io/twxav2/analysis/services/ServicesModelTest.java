package io.twxav2.analysis.services;

import io.twxav2.analysis.services.Services.Model;
import io.twxav2.analysis.services.Services.Service;
import io.twxav2.ingest.TwxParseResult.ParsedObject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static io.twxav2.analysis.ModelsFixtures.obj;
import static io.twxav2.analysis.ModelsFixtures.result;
import static io.twxav2.analysis.ModelsFixtures.uuid;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Service/team side (B2g): declarations, steps, transitions, pre/post
 * script lines (CR view), team member counts — on synthetic object XML.
 */
class ServicesModelTest {

    private static final String S1 = "1." + uuid('a');
    private static final String T1 = "24." + uuid('b');

    private static Service onlyService(String procBody) {
        var o = obj(S1, "Sn", "twProcess",
                "<twObject><process name=\"Svc\">" + procBody + "</process></twObject>");
        var services = Services.of(result(List.of(o))).services();
        assertEquals(1, services.size());
        return services.get(0);
    }

    @Test
    void ofReturnsPerParseResultTheSameInstance() {
        var pr = result(List.of(obj(S1, "S", "twProcess", "<a><process/></a>")));
        assertSame(Services.of(pr), Services.of(pr));
    }

    @Test
    void withoutProcessElementNoService() {
        var o = obj(S1, "S", "twProcess", "<twObject><anderes/></twObject>");
        assertTrue(Services.of(result(List.of(o))).services().isEmpty());
    }

    @Test
    void brokenXmlAndMissingContentAreSkipped() {
        var kaputt = obj(S1, "S", "twProcess", "<twObject><process>");
        var ohne = obj("1." + uuid('e'), "S2", "twProcess", null);
        var teamKaputt = obj(T1, "T", "twParticipant", "kein xml <<");
        var m = Services.of(result(List.of(kaputt, ohne, teamKaputt)));
        assertTrue(m.services().isEmpty());
        assertTrue(m.teams().isEmpty());
    }

    @Test
    void declarationsWithAndWithoutDefault() {
        var s = onlyService("""
                <processVariable name="zaehler"><hasDefault>true</hasDefault></processVariable>
                <processVariable name="text"/><processType>2</processType>""");
        assertEquals(2, s.vars().size());
        assertEquals("zaehler", s.vars().get(0).name());
        assertTrue(s.vars().get(0).hasDefault());
        assertFalse(s.vars().get(1).hasDefault());
        assertEquals(2, s.processType());
    }

    @Test
    void stepsWithComponentNames() {
        var s = onlyService("""
                <item><name>Pruefen</name><tWComponentName>svcPruefen</tWComponentName></item>
                <item><name>Ohne Komponente</name></item>""");
        assertEquals(2, s.steps().size());
        assertEquals("svcPruefen", s.steps().get(0).component());
        assertEquals("", s.steps().get(1).component());
    }

    @Test
    void transitionsWithoutNamesAsEmpty() {
        var s = onlyService("<link name=\"ok\"/><link/>");
        assertEquals(List.of("ok", ""), s.linkNames());
    }

    @Test
    void prePostScriptLinesAsLineCount() {
        var s = onlyService("""
                <item><name>Schritt</name>
                  <processPrePosts><script>a&#13;b&#13;c</script></processPrePosts>
                </item>
                <item><name>Leer</name><processPrePosts><script>  </script></processPrePosts></item>
                <item><name>Ohne</name></item>""");
        assertEquals(List.of(3), s.prePostScriptLines());
    }

    @Test
    void descriptionNormalTrimmed_isNullEmpty() {
        assertEquals("Fachtext", onlyService("<description>  Fachtext  </description>").description());
        assertEquals("", onlyService("<description isNull=\"true\">alt</description>").description());
        assertEquals("", onlyService("<x/>").description());
    }

    @Test
    void processTypeMissingOrJunkIsMinusOne() {
        assertEquals(-1, onlyService("<x/>").processType());
        assertEquals(-1, onlyService("<processType>keins</processType>").processType());
    }

    @Test
    void nameComesFromProcessAttribNotFromTheFile() {
        assertEquals("Svc", onlyService("<x/>").name());
    }

    @Test
    void teamMembersFromNestedJsonArrays() {
        var o = obj(T1, "Team", "twParticipant", """
                <twObject><participant name="Sachbearbeiter">
                  <jsonData>{ "Users": ["a", "b"], "verschachtelt":
                    { "UserGroups": ["g1"], "tiefer": { "Users": ["c"] } } }</jsonData>
                </participant></twObject>""");
        var m = Services.of(result(List.of(o)));
        assertEquals(1, m.teams().size());
        assertEquals("Sachbearbeiter", m.teams().get(0).name());
        assertEquals(3, m.teams().get(0).users());
        assertEquals(1, m.teams().get(0).groups());
    }

    @Test
    void teamWithoutJsonDataWithoutMembers() {
        var o = obj(T1, "Team", "twParticipant", "<twObject><participant name=\"T\"/></twObject>");
        var team = Services.of(result(List.of(o))).teams().get(0);
        assertEquals(0, team.users());
        assertEquals(0, team.groups());
    }

    @Test
    void participantMissingOrJsonBrokenNoTeam() {
        var ohne = obj(T1, "T", "twParticipant", "<twObject><anderes/></twObject>");
        var jsonKaputt = obj("24." + uuid('c'), "T2", "twParticipant",
                "<twObject><participant><jsonData>{ kaputt</jsonData></participant></twObject>");
        assertTrue(Services.of(result(List.of(ohne, jsonKaputt))).teams().isEmpty());
    }

    @Test
    void foreignPrefixesIgnored() {
        var cv = obj("64." + uuid('d'), "CV", "twCoachView", "<a><process/></a>");
        var m = Services.of(result(List.of(cv)));
        assertTrue(m.services().isEmpty() && m.teams().isEmpty());
    }

    // ---------- countLines ----------

    @Test
    void countLinesCrLfCrAndEmpty() {
        assertEquals(1, Services.countLines(""));
        assertEquals(1, Services.countLines("eine zeile"));
        assertEquals(3, Services.countLines("a\rb\rc"));
        assertEquals(2, Services.countLines("a\nb"));
        assertEquals(2, Services.countLines("a\r\nb"));
        assertEquals(4, Services.countLines("a\rb\r\nc\n"));
    }

    @Test
    void modelListsAreCopies() {
        var pr = result(List.of(obj(S1, "S", "twProcess", "<a><process name=\"P\"/></a>")));
        Model m = Services.of(pr);
        assertThrows(UnsupportedOperationException.class, () -> m.services().add(null));
    }

    @Test
    void parsedObjectWithoutPrefixChecksOnlyContentNull() {
        ParsedObject o = new ParsedObject("99." + uuid('f'), "99", "t", "n", "", "sha", "<x/>");
        var m = Services.of(result(List.of(o)));
        assertTrue(m.services().isEmpty() && m.teams().isEmpty());
    }
}

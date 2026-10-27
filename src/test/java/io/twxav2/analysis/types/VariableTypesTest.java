package io.twxav2.analysis.types;

import io.twxav2.analysis.types.VariableTypes.Assign;
import io.twxav2.ingest.TwxParseResult.ParsedObject;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static io.twxav2.analysis.ModelsFixtures.obj;
import static io.twxav2.analysis.ModelsFixtures.result;
import static io.twxav2.analysis.ModelsFixtures.uuid;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Type assignment (B8): declaration cast to business data types, member kinds
 * from the class JSON and literal assignments to tw.local via linear scan.
 */
class VariableTypesTest {

    private static final String CLASS_A = uuid('a');
    private static final String CLASS_B = uuid('b');
    private static final String SVC = "1." + uuid('s');

    /** 12.x class with model JSON. */
    private static ParsedObject bizClass(String uuidPart, String json) {
        return obj("12." + uuidPart, "Klasse", "twClass",
                "<twClass><jsonData>" + json + "</jsonData></twClass>");
    }

    /** Prefix-1 object with one <script> block (lines counted from 1). */
    private static ParsedObject withScript(String... jsLines) {
        String body = String.join("\n", jsLines);
        return obj(SVC, "Svc", "twProcess", "<twObject><script>\n" + body + "\n</script></twObject>");
    }

    // ---------- of / declarations ----------

    @Test
    void ofReturnsPerParseResultTheSameInstance() {
        var pr = result(List.of());
        assertSame(VariableTypes.of(pr), VariableTypes.of(pr));
    }

    @Test
    void complexVarsFromProcessVariablesAndParameters() {
        var o = obj(SVC, "Svc", "twProcess", """
                <twObject>
                  <processVariable name="kunde"><complexType><classId>/12.%s</classId></complexType></processVariable>
                  <processVariable name="zahl"><complexType/></processVariable>
                  <processParameter name="start"><classId>ns/12.%s</classId></processParameter>
                </twObject>
                """.formatted(CLASS_A, CLASS_B));
        Map<String, String> vars = VariableTypes.of(result(List.of(o))).complexVars(o);
        assertEquals(Map.of("kunde", CLASS_A, "start", CLASS_B), vars);
    }

    @Test
    void complexVarsOnlyPrefixOneAndFiveAndOnlyWithContent() {
        var bpd = obj("25." + uuid('c'), "BPD", "twBPD",
                "<processVariable name=\"k\"><classId>/12." + CLASS_A + "</classId></processVariable>");
        var cv = obj("64." + uuid('d'), "CV", "twCoachView",
                "<processVariable name=\"k\"><classId>/12." + CLASS_A + "</classId></processVariable>");
        var ohne = obj("1." + uuid('e'), "S", "twProcess", null);
        var vt = VariableTypes.of(result(List.of(bpd, cv, ohne)));
        assertEquals(Map.of("k", CLASS_A), vt.complexVars(bpd));
        assertTrue(vt.complexVars(cv).isEmpty());
        assertTrue(vt.complexVars(ohne).isEmpty());
    }

    @Test
    void classIdBadSuffixOrSlashNotCounted() {
        var o = obj(SVC, "Svc", "twProcess", """
                <twObject>
                  <processVariable name="galt"><classId>/12.%s</classId></processVariable>
                  <processVariable name="ohneSlash"><classId>12.%s</classId></processVariable>
                  <processVariable name="keins"><classId>irgendwas</classId></processVariable>
                </twObject>
                """.formatted(CLASS_A, uuid('z').replace('z', 'g')));
        var vars = VariableTypes.of(result(List.of(o))).complexVars(o);
        assertEquals(Map.of("galt", CLASS_A), vars);
    }

    @Test
    void complexVarsArePerObjectSaved() {
        var o = obj(SVC, "Svc", "twProcess",
                "<processVariable name=\"k\"><classId>/12." + CLASS_A + "</classId></processVariable>");
        var vt = VariableTypes.of(result(List.of(o)));
        assertSame(vt.complexVars(o), vt.complexVars(o));
    }

    // ---------- members ----------

    @Test
    void membersFromComplexTypeSequence() {
        var o = bizClass(CLASS_A, """
                { "complexType": { "sequence": { "element": [
                    { "name": "strasse", "type": "{http://lombardi}string" },
                    { "name": "anzahl", "type": "xs:int" },
                    { "name": "aktiv", "type": "boolean" },
                    { "name": "seit", "type": "xs:dateTime" },
                    { "name": "typ", "type": "Address" },
                    { "name": "namenlos" } ] } } }""");
        var members = VariableTypes.of(result(List.of(o))).members(CLASS_A);
        assertEquals(Map.of("strasse", "str", "anzahl", "num", "aktiv", "bool",
                "seit", "date", "typ", "other", "namenlos", "other"), members);
    }

    @Test
    void membersOverRootElementWithComplexType() {
        var o = bizClass(CLASS_A, """
                { "element": { "complexType": { "sequence": { "element":
                    { "name": "field", "type": "positiveInteger" } } } } }""");
        assertEquals(Map.of("field", "num"),
                VariableTypes.of(result(List.of(o))).members(CLASS_A));
    }

    @Test
    void memberWithoutTypeWithForeignReferenceIsComplex() {
        var o = bizClass(CLASS_A, """
                { "complexType": { "sequence": { "element": [
                    { "name": "unterObjekt", "otherAttributes": { "refid": "12.%s" } },
                    { "name": "frei" } ] } } }""".formatted(CLASS_B));
        var members = VariableTypes.of(result(List.of(o))).members(CLASS_A);
        assertEquals(Map.of("unterObjekt", "complex", "frei", "other"), members);
    }

    @Test
    void duplicateMemberKeepsFirstEntry() {
        var o = bizClass(CLASS_A, """
                { "complexType": { "sequence": { "element": [
                    { "name": "x", "type": "string" },
                    { "name": "x", "type": "int" } ] } } }""");
        assertEquals("str", VariableTypes.of(result(List.of(o))).members(CLASS_A).get("x"));
    }

    @Test
    void membersUnknownOrBrokenClassEmpty() {
        var kaputt = bizClass(CLASS_A, "{ json");
        var keinJson = obj("12." + CLASS_B, "K", "twClass", "<twClass/>");
        var vt = VariableTypes.of(result(List.of(kaputt, keinJson)));
        assertTrue(vt.members(CLASS_A).isEmpty());
        assertTrue(vt.members(CLASS_B).isEmpty());
        assertTrue(vt.members(uuid('q')).isEmpty());
    }

    @Test
    void membersUnpackEntityEncodedJsonAndReturnNumbers() {
        var o = bizClass(CLASS_A,
                "{ &quot;complexType&quot;: { &quot;sequence&quot;: { &quot;element&quot;:"
                        + " { &quot;name&quot;: &quot;a&quot;, &quot;type&quot;: &quot;double&quot; } } } }");
        assertEquals(Map.of("a", "num"), VariableTypes.of(result(List.of(o))).members(CLASS_A));
    }

    // ---------- assignments ----------

    @Test
    void assignmentsWithLineKindAndRawText() {
        var o = withScript(
                "tw.local.a = 1;",          // fragment line 1 (clean trims the surrounding newline)
                "tw.local.b = \"Text\";");   // fragment line 2
        List<Assign> as = VariableTypes.of(result(List.of(o))).assignments(o);
        assertEquals(2, as.size());
        assertEquals("a", as.get(0).var());
        assertNull(as.get(0).member());
        assertEquals("num", as.get(0).kind());
        assertEquals(1, as.get(0).line());
        assertEquals("tw.local.a = 1", as.get(0).raw());
        assertTrue(as.get(0).where().startsWith("Script block 1"));
        assertEquals("str", as.get(1).kind());
        assertEquals(2, as.get(1).line());
    }

    @Test
    void memberAssignmentAndMultiplePerLine() {
        var o = withScript("tw.local.kunde.stadt = \"Berlin\"; tw.local.flag = true");
        var as = VariableTypes.of(result(List.of(o))).assignments(o);
        assertEquals(2, as.size());
        assertEquals("kunde", as.get(0).var());
        assertEquals("stadt", as.get(0).member());
        assertEquals("str", as.get(0).kind());
        assertEquals("flag", as.get(1).var());
        assertEquals("bool", as.get(1).kind());
    }

    @Test
    void comparisonAndAccumulationAreNoAssignments() {
        var o = withScript(
                "tw.local.z == 5;",
                "tw.local.w += 3;",
                "tw.local.v >= 2;",
                "tw.local.u =! 4;");
        assertTrue(VariableTypes.of(result(List.of(o))).assignments(o).isEmpty());
    }

    @Test
    void hitInCommentOrStringFallsOut() {
        var o = withScript(
                "var s = \"tw.local.x = 1\";",
                "// tw.local.y = 2",
                "/* tw.local.k = 3 */ tw.local.real = 9;");
        var as = VariableTypes.of(result(List.of(o))).assignments(o);
        assertEquals(1, as.size());
        assertEquals("real", as.get(0).var());
    }

    @Test
    void unenclinedLiteralsAreDiscarded() {
        var o = withScript(
                "tw.local.o = foo;",           // not a literal
                "tw.local.n = 5x;",            // word character after number
                "tw.local.d = 1.2.3;",         // dot after number
                "tw.local.m = \"zwei",         // string without end on the same line
                "zeilen\");",
                "tw.local.neg = -12;");        // minus allowed
        var as = VariableTypes.of(result(List.of(o))).assignments(o);
        assertEquals(List.of("neg"), as.stream().map(Assign::var).toList());
        assertEquals("num", as.get(0).kind());
    }

    @Test
    void coachViewWithoutScriptBlockNoAssignments() {
        var cv = obj("64." + uuid('f'), "CV", "twCoachView", "<twCoachView>tw.local.x = 1</twCoachView>");
        assertTrue(VariableTypes.of(result(List.of(cv))).assignments(cv).isEmpty());
    }

    @Test
    void assignmentsFromSecondScriptBlockWithLineOffset() {
        var o = obj(SVC, "Svc", "twProcess", """
                <twObject>
                  <script name="erst">tw.local.a = 1</script>
                  <script name="zweit">
                tw.local.b = 2</script>
                </twObject>""");
        var as = VariableTypes.of(result(List.of(o))).assignments(o);
        assertEquals(2, as.size());
        // clean() trims the leading newline: line = opening line of the block (3)
        assertEquals(3, as.get(1).line());
        assertTrue(as.get(1).where().contains("zweit"));
    }

    // ---------- literalKind ----------

    @Test
    void namesLiteralKinds() {
        assertEquals("str", VariableTypes.literalKind("\"x\""));
        assertEquals("str", VariableTypes.literalKind("'x'"));
        assertEquals("num", VariableTypes.literalKind("42"));
        assertEquals("num", VariableTypes.literalKind("-1"));
        assertEquals("bool", VariableTypes.literalKind("true"));
        assertEquals("bool", VariableTypes.literalKind("false"));
        assertEquals("other", VariableTypes.literalKind("tw.local.x"));
    }

    // ---------- gaps: linear-scan edges and JSON lists ----------

    @Test
    void twLocalWithoutWordAndLiteralEdges() {
        var o = withScript(
                "tw.local.? = 1;",         // no word character after tw.local. -> continue
                "tw.local.d = 1.5;",       // decimal number
                "tw.local.g = false;",     // false alternative
                "tw.local.z = \"abc");     // string to end of line without close -> discarded
        var as = VariableTypes.of(result(List.of(o))).assignments(o);
        assertEquals(List.of("d", "g"), as.stream().map(Assign::var).toList());
        assertEquals("num", as.get(0).kind());
        assertEquals("bool", as.get(1).kind());
    }

    @Test
    void memberAsJsonArraysOnAllLevels() {
        var o = bizClass(CLASS_A, """
                { "complexType": [ { "sequence": [ { "element": [
                    { "name": "a", "type": "string" },
                    { "name": "b", "type": "int" } ] } ] } ] }""");
        assertEquals(Map.of("a", "str", "b", "num"),
                VariableTypes.of(result(List.of(o))).members(CLASS_A));
    }

    @Test
    void declarationWithoutBlockCloseCountNot() {
        // regex match without a following '>' before end of text -> blockStart <= 0 -> skip
        var o = obj(SVC, "Svc", "twProcess", "<twObject><processVariable name=\"x");
        assertTrue(VariableTypes.of(result(List.of(o))).complexVars(o).isEmpty());
    }
}

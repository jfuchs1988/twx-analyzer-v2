package io.twxav2.analysis.bizdata;

import io.twxav2.analysis.bizdata.BusinessData.Type;
import io.twxav2.ingest.TwxParseResult.ParsedObject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static io.twxav2.analysis.ModelsFixtures.obj;
import static io.twxav2.analysis.ModelsFixtures.result;
import static io.twxav2.analysis.ModelsFixtures.uuid;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Business Data Types (B2f): wrapper/import/shared detection, props collection
 * (arbitrarily deep, single and array form), doc check against empty content.
 */
class BusinessDataTest {

    private static final String T1 = "12." + uuid('a');
    private static final String T2 = "12." + uuid('b');

    private static ParsedObject twClass(String json) {
        return obj(T1, "Objekttyp", "twClass",
                "<twClass name=\"Objekttyp\"><jsonData>" + json + "</jsonData></twClass>");
    }

    private static Type only(String json) {
        var types = BusinessData.of(result(List.of(twClass(json)))).types();
        assertEquals(1, types.size());
        return types.get(0);
    }

    @Test
    void ofReturnsPerParseResultTheSameInstance() {
        var pr = result(List.of(twClass("{}")));
        assertSame(BusinessData.of(pr), BusinessData.of(pr));
    }

    @Test
    void onlyTwelveWithContentIsType() {
        var pr = result(List.of(
                twClass("{}"),
                obj("1." + uuid('e'), "Svc", "twProcess", "<jsonData>{}</jsonData>"),
                obj(T2, "Ohne Inhalt", "twClass", null)));
        assertEquals(1, BusinessData.of(pr).types().size());
    }

    @Test
    void withoutJsonDataElementMissingTheType() {
        var o = obj(T1, "T", "twClass", "<twClass name=\"T\"/>");
        assertTrue(BusinessData.of(result(List.of(o))).types().isEmpty());
    }

    @Test
    void unreadableOrForeignJsonSkips() {
        var kaputt = obj(T1, "T", "twClass", "<jsonData>{ keine json</jsonData>");
        var array = obj(T2, "T2", "twClass", "<jsonData>[1,2]</jsonData>");
        assertTrue(BusinessData.of(result(List.of(kaputt, array))).types().isEmpty());
    }

    @Test
    void nameFromTwClassAttribOtherwiseObjectNameOtherwiseId() {
        assertEquals("Objekttyp", only("{}").name());
        var ohne = obj(T1, "Objektname", "twClass", "<twClass><jsonData>{}</jsonData></twClass>");
        assertEquals("Objektname", BusinessData.of(result(List.of(ohne))).types().get(0).name());
        var ganzOhne = new ParsedObject(T2, "12", "twClass", null, "", "sha",
                "<twClass><jsonData>{}</jsonData></twClass>");
        assertEquals(T2, BusinessData.of(result(List.of(ganzOhne))).types().get(0).name());
    }

    @Test
    void wrapperRecognizedAtRootElement() {
        Type t = only("""
                { "rootElement": { "name": "nachricht", "itemRef": "{ns}Nachricht" } }""");
        assertTrue(t.wrapper());
        assertFalse(t.manual());
    }

    @Test
    void importRecognizedAtSwaggerNamespace() {
        Type t = only("""
                { "targetNamespace": "externalservice/swagger/fremdsystem", "element": { "name": "x" } }""");
        assertTrue(t.imported());
        assertFalse(t.manual());
    }

    @Test
    void handWrittenIsNeitherWrapperNorImport() {
        Type t = only("""
                { "targetNamespace": "http://fachlich", "complexType": { "name": "Adr" } }""");
        assertTrue(t.manual());
    }

    @Test
    void sharedDeepInAppinfoAlsoAsArrayShape() {
        assertTrue(only("""
                { "complexType": { "annotation": { "appinfo": { "shared": true } } } }""").shared());
        assertTrue(only("""
                { "a": [ { "b": { "appinfo": { "shared": [true] } } } ] }""").shared());
        assertFalse(only("""
                { "a": { "appinfo": { "shared": false } } }""").shared());
        assertFalse(only("""
                { "a": { "appinfo": { "shared": "true" } } }""").shared());
    }

    @Test
    void propsInSingleShapeAndWithType() {
        Type t = only("""
                { "complexType": { "sequence": { "element":
                    { "name": "strasse", "type": "{http://lombardi}String" } } } }""");
        assertEquals(1, t.props().size());
        assertEquals("strasse", t.props().get(0).name());
        assertEquals("{http://lombardi}String", t.props().get(0).type());
    }

    @Test
    void propsInArrayShapeAndWithoutTypeAsNull() {
        Type t = only("""
                { "complexType": { "sequence": { "element": [
                    { "name": "a", "type": "String" },
                    { "name": "b" } ] } } }""");
        assertEquals(2, t.props().size());
        assertNull(t.props().get(1).type());
    }

    @Test
    void attributeCountAsProps() {
        Type t = only("""
                { "complexType": { "attribute": { "name": "version", "type": "int" } } }""");
        assertEquals(List.of("version"), t.props().stream().map(p -> p.name()).toList());
    }

    @Test
    void propsArbitraryDeepNested() {
        Type t = only("""
                { "complexType": { "sequence": { "element": { "name": "vater", "type": "X",
                     "complexType": { "sequence": { "element": { "name": "kind", "type": "Y" } } }
                   } } } }""");
        assertEquals(List.of("vater", "kind"), t.props().stream().map(p -> p.name()).toList());
    }

    @Test
    void rootDocsOverComplexTypeOrElementOrRoot() {
        assertTrue(only("""
                { "complexType": { "annotation": { "documentation": { "content": ["Text"] } } } }""")
                .rootHasDoc());
        assertTrue(only("""
                { "element": [ { "annotation": { "documentation": { "content": "Text" } } } ] }""")
                .rootHasDoc());
        // listify does not see direct text on the root annotation — only object/array values
        assertFalse(only("""
                { "annotation": { "documentation": "Text" } }""").rootHasDoc());
        assertFalse(only("""
                { "complexType": { "name": "X" } }""").rootHasDoc());
    }

    @Test
    void propDocsAtAttribute() {
        Type t = only("""
                { "element": [
                  { "name": "mit", "annotation": { "documentation": { "content": ["klartext"] } } },
                  { "name": "ohne" } ] }""");
        assertTrue(t.props().get(0).hasDoc());
        assertFalse(t.props().get(1).hasDoc());
    }

    @Test
    void emptyDocsIsNoDocs() {
        assertFalse(only("""
                { "annotation": { "documentation": { "content": "" } } }""").rootHasDoc());
        assertFalse(only("""
                { "annotation": { "documentation": { "content": {} } } }""").rootHasDoc());
        assertFalse(only("""
                { "annotation": { "documentation": { "content": [] } } }""").rootHasDoc());
        assertFalse(only("""
                { "annotation": { "documentation": { "content": [{}] } } }""").rootHasDoc());
    }

    @Test
    void documentationAsTextArrayYesDirectTextNo() {
        // listify ignores raw text nodes: direct "documentation": "text" does not count,
        // but the text entry in the array does (real TWX format is the object in the array)
        assertFalse(only("""
                { "annotation": { "documentation": "nur text" } }""").rootHasDoc());
        assertTrue(only("""
                { "annotation": { "documentation": ["im array"] } }""").rootHasDoc());
        assertTrue(only("""
                { "annotation": { "documentation": [ { "content": "im objekt" } ] } }""")
                .rootHasDoc());
    }

    @Test
    void entityEncodedJsonDataIsUnpacked() {
        var o = obj(T1, "T", "twClass",
                "<twClass name=\"T\"><jsonData>{ &quot;rootElement&quot;: { &quot;name&quot;: &quot;n&quot; } }"
                        + "</jsonData></twClass>");
        assertTrue(BusinessData.of(result(List.of(o))).types().get(0).wrapper());
    }

    @Test
    void propsCapAt400() {
        StringBuilder sb = new StringBuilder("{\"element\":[");
        for (int i = 0; i < 450; i++) {
            sb.append(i > 0 ? "," : "").append("{\"name\":\"f").append(i).append("\"}");
        }
        sb.append("]}");
        assertEquals(400, only(sb.toString()).props().size());
    }

    @Test
    void deeperCapEndsRecursion() {
        // 15-level sequence.element chain each with one named element:
        // the last levels lie beyond MAX_DEPTH (30) and no longer count.
        String node = "{\"name\":\"f14\",\"complexType\":{\"sequence\":{}}}";
        for (int i = 13; i >= 0; i--) {
            node = "{\"name\":\"f" + i + "\",\"complexType\":{\"sequence\":{\"element\":" + node + "}}}";
        }
        var props = only("{\"element\":" + node + "}").props();
        assertTrue(props.size() >= 8 && props.size() < 15,
                "erwartet abgeschnittene Zahl, war: " + props.size());
        assertEquals("f0", props.get(0).name());
    }

    @Test
    void unescNullAndClearText() {
        assertEquals("", BusinessData.unesc(null));
        assertEquals("abc", BusinessData.unesc("abc"));
        assertEquals("<&>", BusinessData.unesc("&lt;&amp;&gt;"));
    }
}

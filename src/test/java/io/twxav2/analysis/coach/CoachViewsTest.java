package io.twxav2.analysis.coach;

import io.twxav2.analysis.coach.CoachViews.Cv;
import io.twxav2.ingest.TwxParseResult.ParsedObject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static io.twxav2.analysis.ModelsFixtures.obj;
import static io.twxav2.analysis.ModelsFixtures.result;
import static io.twxav2.analysis.ModelsFixtures.uuid;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Coach view model (B4): layout view, inline scripts, lifecycle JS,
 * config options, bindings — all on synthetic 64.x object XML.
 */
class CoachViewsTest {

    private static final String CV1 = "64." + uuid('a');
    private static final String CV2 = "64." + uuid('b');
    private static final String CHILD = "64." + uuid('c');

    /** Complete view XML with layout, one JS block and one CSS block. */
    private static ParsedObject fullView() {
        return obj(CV1, "Kopfzeile", "twCoachView", """
                <twCoachView>
                  <layout>&lt;ns2:layoutItem type="widget"/&gt;&lt;ns2:layoutItem type="text"/&gt;\
                &lt;ns2:viewUUID&gt;%s&lt;/ns2:viewUUID&gt;</layout>
                  <inlineScript name="Start"><scriptType>JS</scriptType>\
                <scriptBlock>var a = 1;</scriptBlock></inlineScript>
                  <inlineScript name="Optik"><scriptType>CSS</scriptType>\
                <scriptBlock>.x{color:red}</scriptBlock></inlineScript>
                  <loadJsFunction>console.log("load");</loadJsFunction>
                  <validateJsFunction></validateJsFunction>
                  <configOption name="titel"><label>Titel</label>\
                <propertyType>String</propertyType><isList>false</isList></configOption>
                  <configOption name="werte"><label>Werte</label>\
                <propertyType>String</propertyType><isList>true</isList></configOption>
                  <bindingType>x</bindingType><bindingType>y</bindingType>
                  <previewImage>BASE64</previewImage>
                  <isMobileReady>true</isMobileReady>
                </twCoachView>
                """.formatted(CHILD));
    }

    @Test
    void ofReturnsPerParseResultTheSameInstance() {
        var pr = result(List.of(fullView()));
        assertSame(CoachViews.of(pr), CoachViews.of(pr));
    }

    @Test
    void onlySixFourWithContentAreViews() {
        var pr = result(List.of(
                fullView(),
                obj("1." + uuid('e'), "Svc", "twProcess", "<x/>"),
                obj(CV2, "Ohne Inhalt", "twCoachView", null)));
        var cv = CoachViews.of(pr);
        assertEquals(1, cv.views().size());
        assertEquals(CV1, cv.views().get(0).id());
    }

    @Test
    void viewAfterIdAndUnknownNull() {
        var pr = result(List.of(fullView()));
        var cv = CoachViews.of(pr);
        assertNotNull(cv.view(CV1));
        assertNull(cv.view("64." + uuid('z')));
    }

    @Test
    void layoutCountItemsCollectsViewRefs() {
        Cv cv = CoachViews.of(result(List.of(fullView()))).view(CV1);
        assertEquals(2, cv.layoutItems());
        assertEquals(java.util.Set.of(CHILD), cv.viewRefs());
    }

    @Test
    void layoutMissingYieldsNullItemsEmptyRefs() {
        var o = obj(CV1, "Blanko", "twCoachView", "<twCoachView/>");
        Cv cv = CoachViews.of(result(List.of(o))).view(CV1);
        assertEquals(0, cv.layoutItems());
        assertTrue(cv.viewRefs().isEmpty());
    }

    @Test
    void inlineScriptsWithNameTypeAndUnpackedCode() {
        Cv cv = CoachViews.of(result(List.of(fullView()))).view(CV1);
        assertEquals(2, cv.scripts().size());
        var js = cv.scripts().get(0);
        assertEquals("Start", js.name());
        assertEquals("JS", js.type());
        assertEquals("var a = 1;", js.code());
        assertEquals("CSS", cv.scripts().get(1).type());
    }

    @Test
    void scriptTypeWithoutSpecificationIsJs() {
        var o = obj(CV1, "V", "twCoachView",
                "<inlineScript name=\"x\"><scriptBlock>code</scriptBlock></inlineScript>");
        var s = CoachViews.of(result(List.of(o))).view(CV1).scripts().get(0);
        assertEquals("JS", s.type());
        assertEquals("code", s.code());
    }

    @Test
    void inlineScriptWithoutNamesGetsPlaceholder() {
        var o = obj(CV1, "V", "twCoachView",
                "<inlineScript><scriptBlock>code</scriptBlock></inlineScript>");
        assertEquals("Inline Javascript",
                CoachViews.of(result(List.of(o))).view(CV1).scripts().get(0).name());
    }

    @Test
    void scriptCodeIsEntityUnpacked() {
        var o = obj(CV1, "V", "twCoachView",
                "<inlineScript><scriptBlock>if (a &lt; b) { a(); }</scriptBlock></inlineScript>");
        assertEquals("if (a < b) { a(); }",
                CoachViews.of(result(List.of(o))).view(CV1).scripts().get(0).code());
    }

    @Test
    void inlineJsCharsDoNotCountCss() {
        Cv cv = CoachViews.of(result(List.of(fullView()))).view(CV1);
        // "var a = 1;" (10) + loadJsFunction (22) — CSS block not counted.
        assertEquals("var a = 1;".length() + "console.log(\"load\");".length(), cv.inlineJsChars());
    }

    @Test
    void lifecycleFunctionsOnlyWhenFilled() {
        Cv cv = CoachViews.of(result(List.of(fullView()))).view(CV1);
        assertEquals(java.util.Set.of("loadJsFunction"), cv.lifecycleJs().keySet());
        assertEquals("console.log(\"load\");", cv.lifecycleJs().get("loadJsFunction"));
    }

    @Test
    void lifecycleAllFieldsRecognized() {
        var o = obj(CV1, "V", "twCoachView", """
                <viewJsFunction>v()</viewJsFunction>
                <changeJsFunction>c()</changeJsFunction>
                <collaborationJsFunction>co()</collaborationJsFunction>""");
        var lc = CoachViews.of(result(List.of(o))).view(CV1).lifecycleJs();
        assertEquals(3, lc.size());
        assertTrue(lc.containsKey("viewJsFunction") && lc.containsKey("changeJsFunction")
                && lc.containsKey("collaborationJsFunction"));
    }

    @Test
    void configOptionsWithLabelTypeAndList() {
        Cv cv = CoachViews.of(result(List.of(fullView()))).view(CV1);
        assertEquals(2, cv.configOptions().size());
        var opt = cv.configOptions().get(0);
        assertEquals("titel", opt.name());
        assertEquals("Titel", opt.label());
        assertEquals("String", opt.propertyType());
        assertFalse(opt.list());
        assertTrue(cv.configOptions().get(1).list());
    }

    @Test
    void bindingTypesAndPreviewImageAndMobile() {
        Cv cv = CoachViews.of(result(List.of(fullView()))).view(CV1);
        assertEquals(2, cv.bindingTypes());
        assertTrue(cv.previewImage());
        assertTrue(cv.isMobileReady());
    }

    @Test
    void withoutPreviewAndMobileIsBothWrong() {
        var o = obj(CV1, "V", "twCoachView", "<twCoachView/>");
        Cv cv = CoachViews.of(result(List.of(o))).view(CV1);
        assertEquals(0, cv.bindingTypes());
        assertFalse(cv.previewImage());
        assertFalse(cv.isMobileReady());
    }

    @Test
    void previewAdvHtmlCountsAlsoAsPreview() {
        var o = obj(CV1, "V", "twCoachView", "<previewAdvHtml>h</previewAdvHtml>");
        assertTrue(CoachViews.of(result(List.of(o))).view(CV1).previewImage());
    }

    @Test
    void allJsCombinesJsSourcesWithoutCss() {
        Cv cv = CoachViews.of(result(List.of(fullView()))).view(CV1);
        String all = cv.allJs();
        assertTrue(all.contains("var a = 1;"));
        assertTrue(all.contains("console.log(\"load\");"));
        assertFalse(all.contains("color:red"));
    }

    @Test
    void multipleViewsKeepOrder() {
        var a = obj(CV1, "A", "twCoachView", "<a/>");
        var b = obj(CV2, "B", "twCoachView", "<b/>");
        var vs = CoachViews.of(result(List.of(a, b))).views();
        assertEquals(List.of(CV1, CV2), vs.stream().map(Cv::id).toList());
    }

    // ---------- Helpers ----------

    @Test
    void groupFindsFirstGroupOrNull() {
        assertEquals("x", CoachViews.group("<a>x</a><a>y</a>", "<a>(.*?)</a>"));
        assertNull(CoachViews.group("<a>x</a>", "<b>(.*?)</b>"));
    }

    @Test
    void unescKnowsNumericEntitiesAndNull() {
        assertEquals("a\r\nb", CoachViews.unesc("a&#xD;&#10;b"));
        assertEquals("a\r\nb", CoachViews.unesc("a&#13;&#10;b"));
        assertEquals("", CoachViews.unesc(null));
        assertEquals("kein entity", CoachViews.unesc("kein entity"));
    }
}

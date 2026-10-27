package io.twxav2.analysis.ref;

import io.twxav2.analysis.RuleContext;
import io.twxav2.analysis.TwxRule.Hit;
import io.twxav2.analysis.ref.rules.Finding;
import io.twxav2.analysis.ref.rules.Rule;
import io.twxav2.analysis.ref.rules.Severity;
import io.twxav2.ingest.TwxParseResult;
import io.twxav2.ingest.TwxParseResult.Dependency;
import io.twxav2.ingest.TwxParseResult.Manifest;
import io.twxav2.ingest.TwxParseResult.ParsedObject;
import io.twxav2.ingest.TwxParseResult.ParsedToolkit;
import io.twxav2.ingest.TwxParseResult.ParseError;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Adapter test: catalog metadata from the catalog rule, Finding->Hit mapping,
 * shared runtime context (scratch).
 */
class CatalogRuleTest {

    private static TwxParseResult parsed() {
        return new TwxParseResult(
                new Manifest("Demo", "DEMO", "d", "Main", "1.0", "sd", "2026-01-01",
                        "8.6.9", "bd", false, List.of()),
                List.of(new ParsedObject("61.aaa", "61", "service", "Svc", "desc",
                        "sha", "<service/>")),
                List.of(new ParsedToolkit("tk-1", "TK", "TKP", "1.2", "sha2", 10, 0, List.of(),
                        List.of(), java.util.Map.of())),
                List.of());
    }

    /** Probe: emits one object finding (with location/line/evidence) and one app finding. */
    private static Rule probe(boolean review) {
        Rule r = new Rule("R-TST-001", "Probe title", "ProbeCat", Severity.MAJOR,
                "what is checked", "how to fix", "REF-1") {
            @Override
            public void check(io.twxav2.analysis.ref.rules.RuleContext c, List<Finding> out) {
                var o = c.twx.app.objects.get("61.aaa");
                Finding f = Finding.of(this, o, "item", "Item", "step X", "boom", "evidence text");
                f.line = 7;
                f.snippet = "var x;";
                out.add(f);
                out.add(Finding.of(this, null, "", "", "", "app level", ""));
            }
        };
        return review ? r.review() : r;
    }

    @Test
    void metadataFromCatalogRule() {
        var rule = new CatalogRule("R-SVC-018", probe(false).impact(3));
        assertEquals("R-SVC-018", rule.id());
        assertEquals("ProbeCat", rule.categoryEn());
        assertEquals(io.twxav2.model.Finding.Severity.MAJOR, rule.defaultSeverity());
        assertEquals(3, rule.impact());
        // German catalog text (ruletexts-de.properties), English in the *En field.
        assertEquals(RuleTextsDe.get("R-SVC-018", "title"), rule.title());
        assertTrue(rule.title().startsWith("Zu viele verschachtelte"), "DE-Titel erwartet, war: " + rule.title());
        assertEquals("Probe title", rule.titleEn());
        assertEquals(RuleTextsDe.get("R-SVC-018", "rationale"), rule.rationale());
        assertEquals("what is checked", rule.rationaleEn());
        assertEquals(RuleTextsDe.get("R-SVC-018", "guidance"), rule.guidance());
        assertEquals("how to fix", rule.guidanceEn());
    }

    @Test
    void withoutCatalogTextFallsTheAdapterOnEnglishBack() {
        var rule = new CatalogRule("R-TST-999", probe(false)); // R-TST-999 has no DE texts
        assertEquals("Probe title", rule.title());
        assertEquals("Probe title", rule.titleEn());
        assertEquals("what is checked", rule.rationale());
        assertEquals("how to fix", rule.guidance());
        assertEquals("ProbeCat", rule.category());
    }

    @Test
    void designerGeneratedObjectsSuppressFindings() {
        assertTrue(CatalogRule.isDesignerGenerated("Msg_305_1a2b3c4d_1111_2222_3333_444455556666"));
        assertTrue(CatalogRule.isDesignerGenerated("ItmDef_9_00112233_aaaa_bbbb_cccc_0123456789ab"));
        assertFalse(CatalogRule.isDesignerGenerated("MyService"));
        assertFalse(CatalogRule.isDesignerGenerated("Msg_abc_1a2b3c4d_1111_2222_3333_444455556666"));
        assertFalse(CatalogRule.isDesignerGenerated(null));

        Rule r = new Rule("R-TST-003", "t", "C", Severity.MINOR, "d", "r", "") {
            @Override
            public void check(io.twxav2.analysis.ref.rules.RuleContext c, List<Finding> out) {
                var app = c.twx.app;
                var gen = new io.twxav2.analysis.ref.model.TwxObject("12.99999999-9999-4999-8999-999999999999",
                        "Msg_305_1a2b3c4d_1111_2222_3333_444455556666", "twClass", "v", "<x/>", app);
                var ok = new io.twxav2.analysis.ref.model.TwxObject("12.88888888-8888-4888-8888-888888888888",
                        "FachlichesObjekt", "twClass", "v", "<x/>", app);
                out.add(Finding.of(this, gen, "", "", "", "generisch", ""));
                out.add(Finding.of(this, ok, "", "", "", "fachlich", ""));
            }
        };
        List<Hit> hits = new CatalogRule("R-APP-001", r).analyze(new RuleContext(parsed(), Map.of()));
        assertEquals(1, hits.size());
        assertEquals("FachlichesObjekt", hits.get(0).objectName());
    }

    @Test
    void findingsOnHitsMapped() {
        var rule = new CatalogRule("R-APP-001", probe(false));
        List<Hit> hits = rule.analyze(new RuleContext(parsed(), Map.of()));
        assertEquals(2, hits.size());
        Hit obj = hits.get(0);
        assertEquals("61.aaa", obj.objectId());
        assertEquals("Svc", obj.objectName());
        assertEquals("step X", obj.location());
        assertEquals(7, obj.line());
        assertEquals("var x;", obj.snippet());
        assertEquals("boom — evidence text", obj.message());
        assertEquals(obj.message(), obj.messageEn());
        assertFalse(obj.needsReview());
        Hit app = hits.get(1);
        assertNull(app.objectId());
        assertNull(app.objectName());
        assertNull(app.location());
        assertNull(app.line());
        assertEquals("app level", app.message());
    }

    @Test
    void middleConfidenceMarkedReview() {
        var rule = new CatalogRule("R-APP-001", probe(true));
        Hit first = rule.analyze(new RuleContext(parsed(), Map.of())).get(0);
        assertTrue(first.needsReview());
    }

    @Test
    void messageIsBounded() {
        String lang = "x".repeat(700);
        Rule r = new Rule("R-TST-002", "t", "C", Severity.MINOR, "d", "r", "") {
            @Override
            public void check(io.twxav2.analysis.ref.rules.RuleContext c, List<Finding> out) {
                out.add(Finding.of(this, null, "", "", "", lang, ""));
            }
        };
        Hit hit = new CatalogRule("R-APP-001", r).analyze(new RuleContext(parsed(), Map.of())).get(0);
        assertEquals(593, hit.message().length());
        assertTrue(hit.message().endsWith("..."));
    }

    @Test
    void runContextIsShared() {
        var ctx = new RuleContext(parsed(), Map.of(), new HashMap<>());
        var a = new CatalogRule("R-APP-001", probe(false));
        var b = new CatalogRule("R-TST-001", probe(false));
        var rc1 = CatalogRule.refContext(ctx);
        assertSame(rc1, CatalogRule.refContext(ctx));
        assertSame(rc1.twx, CatalogRule.refContext(ctx).twx);
        a.analyze(ctx);
        b.analyze(ctx);
        assertSame(rc1, ctx.scratch().get(CatalogRule.SCRATCH_KEY));
        // own model: contains the object
        assertEquals("Svc", rc1.twx.app.objects.get("61.aaa").name);
    }

    @Test
    void analysisWithoutContextUsesDefaults() {
        List<Hit> hits = new CatalogRule("R-APP-001", probe(false)).analyze(parsed());
        assertEquals(2, hits.size());
    }
}

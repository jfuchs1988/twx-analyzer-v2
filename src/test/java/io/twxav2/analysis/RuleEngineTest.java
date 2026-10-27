package io.twxav2.analysis;

import io.twxav2.analysis.RuleEngine.RuleFinding;
import io.twxav2.analysis.TwxRule.Hit;
import io.twxav2.ingest.TwxParseResult;
import io.twxav2.model.Finding;
import io.twxav2.model.Finding.Severity;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RuleEngine: sorting, activation, severity/impact/cap overrides,
 * error isolation of broken rules and threshold overlay. The CDI instance of
 * the rule beans is replaced by a dynamic proxy — pure logic without a container.
 */
class RuleEngineTest {

    private static final TwxParseResult EMPTY = ModelsFixtures.result(List.of());

    /** Simple rule with a fixed hit list. */
    private static class FakeRule implements TwxRule {
        private final String id;
        private final List<Hit> hits;
        FakeRule(String id, Hit... hits) { this.id = id; this.hits = List.of(hits); }
        @Override public String id() { return id; }
        @Override public String title() { return "Titel " + id; }
        @Override public List<Hit> analyze(TwxParseResult result) { return hits; }
    }

    /** Rule that throws — the engine run must still complete. */
    private static class BrokenRule extends FakeRule {
        BrokenRule() { super("R-TST-999"); }
        @Override public List<Hit> analyze(TwxParseResult result) {
            throw new IllegalStateException("kaputt");
        }
    }

    @SuppressWarnings("unchecked")
    private static RuleEngine engineWith(List<TwxRule> rules) {
        RuleEngine engine = new RuleEngine();
        engine.rules = (Instance<TwxRule>) Proxy.newProxyInstance(
                RuleEngine.class.getClassLoader(), new Class<?>[] {Instance.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "iterator" -> rules.iterator();
                    case "forEach" -> {
                        rules.forEach((Consumer<? super TwxRule>) args[0]);
                        yield null;
                    }
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        return engine;
    }

    private static RuleSettings settings(String ruleId, java.util.function.Consumer<RuleSettings.Override> tweak) {
        RuleSettings s = new RuleSettings();
        RuleSettings.Override ov = new RuleSettings.Override();
        tweak.accept(ov);
        s.putOverride(ruleId, ov);
        return s;
    }

    @Test
    void withoutSettingsRunsAllWithDefaultEnabled() {
        TwxRule aus = new FakeRule("R-TST-002", new Hit(Severity.INFO, "1.b", "Beta", "txt")) {
            @Override public boolean defaultEnabled() { return false; }
        };
        RuleEngine engine = engineWith(List.of(new FakeRule("R-TST-001",
                new Hit(Severity.MINOR, "1.a", "Alpha", "text")), aus));
        List<RuleFinding> out = engine.run(EMPTY);
        assertEquals(List.of("R-TST-001"), out.stream().map(RuleFinding::ruleId).toList());
        // defaultEnabled=false is armed by the setting
        List<RuleFinding> an = engine.run(EMPTY, settings("R-TST-002", o -> o.enabled = true));
        assertTrue(an.stream().anyMatch(f -> f.ruleId().equals("R-TST-002")));
    }

    @Test
    void deactivationOverwritesDefaultActive() {
        RuleEngine engine = engineWith(List.of(new FakeRule("R-TST-001",
                new Hit(Severity.MAJOR, "1.a", "A", "x"))));
        assertTrue(engine.run(EMPTY).size() == 1);
        assertTrue(engine.run(EMPTY, settings("R-TST-001", o -> o.enabled = false)).isEmpty());
    }

    @Test
    void brokenRuleLoggedAsInfo() {
        RuleEngine engine = engineWith(List.of(new BrokenRule()));
        List<RuleFinding> out = engine.run(EMPTY);
        assertEquals(1, out.size());
        RuleFinding f = out.get(0);
        assertEquals(Severity.INFO, f.severity());
        assertTrue(f.hit().message().contains("kaputt"));
        assertTrue(f.hit().messageEn().contains("R-TST-999 failed"));
    }

    /** Dashboard metric: JavaScript lines across all scripts/handlers in the parse result. */
    @Test
    void scriptMetricsAddiertZeilen() {
        var parsed = ModelsFixtures.result(List.of(ModelsFixtures.obj("64.cv", "CV", "coachView",
                "<coachView name=\"CV\"><loadJsFunction>var a = 1;\nvar b = 2;</loadJsFunction>"
                        + "<inlineScript name=\"Start\"><scriptType>JS</scriptType>"
                        + "<scriptBlock>var c = 3;</scriptBlock></inlineScript>"
                        + "<inlineScript name=\"Optik\"><scriptType>CSS</scriptType>"
                        + "<scriptBlock>.x{color:red}</scriptBlock></inlineScript></coachView>")));
        assertEquals(3, RuleEngine.scriptMetrics(parsed).scriptLines());   // CSS nicht gezaehlt
        assertEquals(0, RuleEngine.scriptMetrics(EMPTY).scriptLines());
    }

    @Test
    void heavyAndImpactOverwriteTheScore() {
        int basis = Finding.weight(Severity.MINOR);
        RuleEngine engine = engineWith(List.of(new FakeRule("R-TST-001",
                new Hit(Severity.MINOR, "1.a", "A", "x"))));
        RuleFinding normal = engine.run(EMPTY).get(0);
        assertEquals(basis, normal.score());                                   // impact 1

        RuleFinding hoch = engine.run(EMPTY, settings("R-TST-001", o -> {
            o.severity = Severity.CRITICAL;
            o.impact = 3;
        })).get(0);
        assertEquals(Severity.CRITICAL, hoch.severity());
        assertEquals(Finding.weight(Severity.CRITICAL) * 3, hoch.score());

        // impact is clamped to 1..3
        assertEquals(Finding.weight(Severity.MINOR) * 3,
                engine.run(EMPTY, settings("R-TST-001", o -> o.impact = 99)).get(0).score());
        assertEquals(Finding.weight(Severity.MINOR),
                engine.run(EMPTY, settings("R-TST-001", o -> o.impact = 0)).get(0).score());
    }

    @Test
    void capBoundedHitWithHint() {
        List<Hit> viele = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            viele.add(new Hit(Severity.MINOR, "1.o" + i, "O" + i, "Treffer " + i));
        }
        RuleEngine engine = engineWith(List.of(new FakeRule("R-TST-001", viele.toArray(Hit[]::new))));
        assertEquals(5, engine.run(EMPTY).size());                 // without cap: everything

        List<RuleFinding> out = engine.run(EMPTY, settings("R-TST-001", o -> o.cap = 2));
        assertEquals(3, out.size());                               // 2 hits + notice
        assertEquals(2, out.stream().filter(f -> !f.hit().message().contains("begrenzt")).count());
        RuleFinding notice = out.stream().filter(f -> f.hit().message().contains("begrenzt"))
                .findFirst().orElseThrow();
        assertEquals(Severity.INFO, notice.severity());
        assertEquals(0, notice.score());

        // cap < 1 is raised to 1
        assertEquals(2, engine.run(EMPTY, settings("R-TST-001", o -> o.cap = 0)).size());
    }

    @Test
    void rankOrderScoreSeverityObjectNameRule() {
        List<TwxRule> rules = List.of(
                new FakeRule("R-TST-002", new Hit(Severity.MINOR, "z", "Beta", "x"),
                        new Hit(Severity.CRITICAL, "y", "alpha", "x")),
                new FakeRule("R-TST-001", new Hit(Severity.MINOR, "a", "Alpha", "x"),
                        new Hit(Severity.MINOR, "b", "beta", "x")));
        List<RuleFinding> out = engineWith(rules).run(EMPTY);
        // 1. score descending: CRITICAL first
        assertEquals(Severity.CRITICAL, out.get(0).severity());
        // same score tier (MINOR): object name case-insensitive, rule ID on a tie
        List<String> names = out.subList(1, out.size()).stream()
                .map(f -> f.hit().objectName()).toList();
        assertEquals(List.of("Alpha", "beta", "Beta"), names);
    }

    @Test
    void nullObjectNameSortsFront() {
        RuleEngine engine = engineWith(List.of(new FakeRule("R-TST-001",
                new Hit(Severity.MINOR, "z", "Z", "x"),
                new Hit(Severity.MINOR, "n", null, "x"))));
        List<RuleFinding> out = engine.run(EMPTY);
        assertNull(out.get(0).hit().objectName());   // "" sorts before "Z"
    }

    @Test
    void thresholdsDefaultsPlusOverlay() {
        List<String> seen = new ArrayList<>();
        TwxRule probe = new FakeRule("R-TST-001") {
            @Override public List<Hit> analyze(RuleContext ctx) {
                seen.add(ctx.th("complexity", -1) + "/" + ctx.th("unbekannt", 7));
                return List.of();
            }
        };
        RuleEngine engine = engineWith(List.of(probe));
        engine.run(EMPTY);
        assertEquals("30/7", seen.get(0));                        // catalog default

        RuleSettings s = new RuleSettings();
        s.thresholds.put("complexity", 99);
        engine.run(EMPTY, s);
        assertEquals("99/7", seen.get(1));                        // snapshot overlay
    }
}

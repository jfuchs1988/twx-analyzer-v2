package io.twxav2.analysis;

import io.twxav2.analysis.ref.CatalogRule;
import io.twxav2.analysis.ref.model.Script;
import io.twxav2.ingest.TwxParseResult;
import io.twxav2.model.Finding.Severity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Runs all registered TwxRule beans over one parse result. */
@ApplicationScoped
public class RuleEngine {

    @Inject
    Instance<TwxRule> rules;

    /**
     * A finding after applying the effective settings: effective severity,
     * score (weight x impact), category, enrichment from the rule's hit.
     */
    public record RuleFinding(String ruleId, String ruleTitle, String category,
                              Severity severity, int score, TwxRule.Hit hit) { }

    public List<RuleFinding> run(TwxParseResult parsed) {
        return run(parsed, null);
    }

    /** Runs all rules; disabled rules are skipped,
     *  severities/impacts overridden per setting. */
    public List<RuleFinding> run(TwxParseResult parsed, RuleSettings settings) {
        List<RuleFinding> all = new ArrayList<>();
        List<TwxRule> sorted = new ArrayList<>();
        rules.forEach(sorted::add);
        sorted.sort(Comparator.comparing(TwxRule::id));
        RuleContext ctx = new RuleContext(parsed, effectiveThresholds(settings));
        for (TwxRule rule : sorted) {
            RuleSettings.Override ov = settings == null ? null : settings.override(rule.id());
            boolean enabled = ov != null && ov.enabled != null
                    ? ov.enabled : rule.defaultEnabled();
            if (!enabled) {
                continue;
            }
            List<TwxRule.Hit> hits;
            try {
                hits = rule.analyze(ctx);
            } catch (RuntimeException | StackOverflowError e) {
                // A faulty rule must not stall the run: record it as INFO
                // (StackOverflowError: Java regex recursion can blow up on mega scripts)
                hits = List.of(new TwxRule.Hit(Severity.INFO, null, null,
                        "Regel " + rule.id() + " fehlgeschlagen: " + e.getMessage(),
                        "Rule " + rule.id() + " failed: " + e.getMessage()));
            }
            int impact = ov != null && ov.impact != null ? Math.clamp(ov.impact, 1, 3) : rule.impact();
            int cap = ov != null && ov.cap != null ? Math.max(1, ov.cap) : rule.cap();
            int used = 0;
            for (TwxRule.Hit hit : hits) {
                if (used >= cap) {
                    all.add(new RuleFinding(rule.id(), rule.title(), rule.category(), Severity.INFO,
                            0, new TwxRule.Hit(Severity.INFO, null, null,
                                    "Regel " + rule.id() + ": Trefferliste auf " + cap
                                            + " begrenzt (Cap erhoehen, wenn vollstaendige Liste gewuenscht).",
                                    "Rule " + rule.id() + ": findings list capped at " + cap
                                            + " (raise the cap if you need the full list).")));
                    break;
                }
                Severity sev = ov != null && ov.severity != null ? ov.severity : hit.severity();
                all.add(new RuleFinding(rule.id(), rule.title(), rule.category(), sev,
                        io.twxav2.model.Finding.weight(sev) * impact, hit));
                used++;
            }
        }
        // ranking: score descending, then severity, then object name, then rule id
        all.sort(Comparator.comparingInt(RuleFinding::score).reversed()
                .thenComparing(f -> f.severity().ordinal())
                .thenComparing(f -> f.hit().objectName() == null ? "" : f.hit().objectName(),
                        String.CASE_INSENSITIVE_ORDER)
                .thenComparing(RuleFinding::ruleId));
        return all;
    }

    /** Effective thresholds: defaults overlaid with setting values. */
    private static java.util.Map<String, Object> effectiveThresholds(RuleSettings settings) {
        java.util.Map<String, Object> t = new java.util.LinkedHashMap<>(RuleSettings.DEFAULT_THRESHOLDS);
        if (settings != null) {
            t.putAll(settings.thresholds);
        }
        return t;
    }

    /** Size metrics of a parse result for the dashboard (B5.3): total JavaScript lines. */
    public record ScriptMetrics(long scriptLines) { }

    /**
     * Total JavaScript lines of the exported application (scripts/conditions/
     * handlers of services, processes and coach views — same extraction as the
     * script rules, application only). Never fails: a broken script model
     * yields 0 instead of stalling the ingest.
     */
    public static ScriptMetrics scriptMetrics(TwxParseResult parsed) {
        try {
            io.twxav2.analysis.ref.rules.RuleContext rc = CatalogRule.refContext(
                    new RuleContext(parsed, effectiveThresholds(null)));
            long lines = 0;
            for (Script s : rc.allScripts()) {
                lines += s.lines();
            }
            return new ScriptMetrics(lines);
        } catch (RuntimeException | StackOverflowError e) {
            return new ScriptMetrics(0);
        }
    }
}

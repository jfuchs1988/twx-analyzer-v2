package io.twxav2.analysis;

import io.twxav2.ingest.TwxParseResult;

import java.util.Map;

/**
 * Context for one rule run: parse result plus effective thresholds.
 * Rules read thresholds through the th() accessors (default in code,
 * overridable via rule settings globally / per snapshot).
 * {@code scratch} carries run-internal derived data (e.g. the fully parsed
 * reference model of the rule catalog) so expensive analyses are built
 * only once per run.
 */
public record RuleContext(TwxParseResult parsed, Map<String, Object> thresholds,
                          Map<String, Object> scratch) {

    /** Run context without derived data. */
    public RuleContext(TwxParseResult parsed, Map<String, Object> thresholds) {
        this(parsed, thresholds, new java.util.HashMap<>());
    }

    public int th(String key, int def) {
        Object v = thresholds.get(key);
        return v instanceof Number n ? n.intValue() : def;
    }

    public long th(String key, long def) {
        Object v = thresholds.get(key);
        return v instanceof Number n ? n.longValue() : def;
    }

    public boolean th(String key, boolean def) {
        Object v = thresholds.get(key);
        return v instanceof Boolean b ? b : def;
    }

    public String th(String key, String def) {
        Object v = thresholds.get(key);
        return v instanceof String s ? s : def;
    }
}

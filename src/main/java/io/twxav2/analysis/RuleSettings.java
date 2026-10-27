package io.twxav2.analysis;

import io.twxav2.model.Finding.Severity;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Effective rule settings (JSON schema for export/import):
 * overrides enablement/severity/impact per rule, plus global thresholds.
 * Merge order: defaults &lt; global settings &lt; snapshot settings.
 */
public class RuleSettings {

    public static class Override {
        public Boolean enabled;
        public Severity severity;
        public Integer impact;    // 1..3
        public Integer cap;       // max. findings per rule and snapshot

        public boolean isEmpty() {
            return enabled == null && severity == null && impact == null && cap == null;
        }
    }

    public Map<String, Override> overrides = new LinkedHashMap<>();
    public Map<String, Object> thresholds = new LinkedHashMap<>();

    /**
     * Auto-exclusion: substrings (case-insensitive) in the object name — e.g.
     * "_deprecated". Applies to project AND toolkit objects.
     */
    public List<String> excludeNamePatterns = new ArrayList<>();
    /** Auto-exclusion: process center tags (exact, case-insensitive); project objects only. */
    public List<String> excludeTags = new ArrayList<>();

    /**
     * Catalog default thresholds (requirement "rating and thresholds").
     * Key = internal threshold names of the rules; the values are the
     * catalog defaults and do not override the limits wired in the code.
     */
    public static final Map<String, Object> DEFAULT_THRESHOLDS = Map.ofEntries(
            Map.entry("toolkits", 25),
            Map.entry("toolkitDepth", 4),
            Map.entry("toolkitSizeMb", 50),
            Map.entry("serviceSteps", 40),
            Map.entry("serviceParams", 15),
            Map.entry("privateVariables", 30),
            Map.entry("coachesPerService", 5),
            Map.entry("complexity", 30),
            Map.entry("stepsBeforeCoach", 5),
            Map.entry("assignmentsPerStep", 10),
            Map.entry("contextAttributes", 150),
            Map.entry("nestedCshs", 25),
            Map.entry("bpdFlowObjects", 30),
            Map.entry("sequentialActivities", 5),
            Map.entry("searchableFields", 25),
            Map.entry("coachControls", 500),
            Map.entry("coachViewJsLines", 300),
            Map.entry("viewResources", 4),
            Map.entry("boAttributes", 50),
            Map.entry("scriptLinesMedium", 100),
            Map.entry("scriptLinesHigh", 300),
            Map.entry("logStatements", 5),
            Map.entry("assetKb", 300));

    public Override override(String ruleId) {
        return overrides.get(ruleId);
    }

    public void putOverride(String ruleId, Override o) {
        overrides.put(ruleId, o);
    }

    /** These settings overlay base (this wins). */
    public RuleSettings mergedWith(RuleSettings base) {
        RuleSettings out = new RuleSettings();
        if (base != null) {
            out.thresholds.putAll(base.thresholds);
            out.overrides.putAll(base.overrides);
        }
        out.thresholds.putAll(this.thresholds);
        this.overrides.forEach((k, v) -> {
            Override merged = new Override();
            Override b = out.overrides.get(k);
            if (b != null) {
                merged.enabled = b.enabled;
                merged.severity = b.severity;
                merged.impact = b.impact;
                merged.cap = b.cap;
            }
            if (v.enabled != null) {
                merged.enabled = v.enabled;
            }
            if (v.severity != null) {
                merged.severity = v.severity;
            }
            if (v.impact != null) {
                merged.impact = v.impact;
            }
            if (v.cap != null) {
                merged.cap = v.cap;
            }
            out.overrides.put(k, merged);
        });
        out.excludeNamePatterns = union(base == null ? null : base.excludeNamePatterns, this.excludeNamePatterns);
        out.excludeTags = union(base == null ? null : base.excludeTags, this.excludeTags);
        return out;
    }

    /** Union without duplicates (case-insensitive), order: base first. */
    public static List<String> union(List<String> a, List<String> b) {
        java.util.LinkedHashMap<String, String> seen = new java.util.LinkedHashMap<>();
        for (List<String> l : java.util.Arrays.asList(a, b)) {
            if (l == null) {
                continue;
            }
            for (String v : l) {
                if (v != null && !v.isBlank()) {
                    seen.putIfAbsent(v.trim().toLowerCase(java.util.Locale.ROOT), v.trim());
                }
            }
        }
        return new ArrayList<>(seen.values());
    }
}

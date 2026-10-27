package io.twxav2.service;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.twxav2.analysis.RuleSettings;
import io.twxav2.model.AnalysisSetting;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import java.util.List;

/**
 * Persistent rule settings: global default row + optional snapshot row.
 * The payload is JSON ({@code {"overrides": {...}, "thresholds": {...}}}) and thus
 * directly exportable/importable.
 */
@ApplicationScoped
public class RuleSettingsService {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    /** Effective settings for an analysis: snapshot overrides global. */
    public RuleSettings effective(Long snapshotId) {
        RuleSettings global = read(null);
        if (snapshotId == null) {
            return global;
        }
        return read(snapshotId).mergedWith(global);
    }

    @Transactional
    public void save(Long snapshotId, String json) {
        RuleSettings parsed;
        try {
            // parse with type checks: unknown severity/type errors (e.g. enabled:"JA",
            // cap:"viel") — otherwise the row would be silently ignored when read (read()
            // fail-open) and the UI showed dead values
            parsed = MAPPER.readValue(json, RuleSettings.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("Ungueltige Einstellungen: " + rootMessage(e), e);
        }
        // Value ranges: impact feeds into the score formula, broken values
        // would make health/gate unpredictable
        for (var en : parsed.overrides.entrySet()) {
            var o = en.getValue();
            if (o == null) {
                continue;
            }
            if (o.impact != null && (o.impact < 1 || o.impact > 3)) {
                throw new IllegalArgumentException("Impact for " + en.getKey() + " must be between 1 and 3");
            }
            if (o.cap != null && o.cap < 0) {
                throw new IllegalArgumentException("Cap for " + en.getKey() + " must not be negative");
            }
        }
        AnalysisSetting row = find(snapshotId);
        if (row == null) {
            row = new AnalysisSetting();
            row.snapshotId = snapshotId;
        }
        row.payload = json;
        row.persist();
    }

    /** shortest usable Jackson message (instead of 6-line nested text). */
    private static String rootMessage(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null && t.getCause() != t) {
            t = t.getCause();
        }
        String m = t.getMessage();
        int nl = m == null ? -1 : m.indexOf('\n');
        m = nl >= 0 ? m.substring(0, nl) : m;
        return m == null || m.isBlank() ? e.getClass().getSimpleName() : m;
    }

    public String rawJson(Long snapshotId) {
        AnalysisSetting row = find(snapshotId);
        if (row != null && row.payload != null && !row.payload.isBlank()) {
            return row.payload;
        }
        return write(defaults(snapshotId));
    }

    /** Default export file: global delivers defaults, snapshot delivers the override. */
    private RuleSettings defaults(Long snapshotId) {
        return new RuleSettings();
    }

    @Transactional
    public boolean deleteSnapshotSettings(Long snapshotId) {
        return AnalysisSetting.delete("snapshotId = ?1", snapshotId) > 0;
    }

    /** Clean up after snapshot deletion. */
    @Transactional
    public void deleteForSnapshot(Long snapshotId) {
        AnalysisSetting.delete("snapshotId = ?1", snapshotId);
    }

    private AnalysisSetting find(Long snapshotId) {
        return snapshotId == null
                ? AnalysisSetting.<AnalysisSetting>find("snapshotId is null").firstResult()
                : AnalysisSetting.<AnalysisSetting>find("snapshotId = ?1", snapshotId).firstResult();
    }

    private RuleSettings read(Long snapshotId) {
        AnalysisSetting row = find(snapshotId);
        if (row == null || row.payload == null || row.payload.isBlank()) {
            return new RuleSettings();
        }
        try {
            return MAPPER.readValue(row.payload, RuleSettings.class);
        } catch (Exception e) {
            return new RuleSettings();   // a broken row does not block the analysis
        }
    }

    private String write(RuleSettings s) {
        try {
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(s);
        } catch (Exception e) {
            return "{}";
        }
    }

    /** Catalog export: defaults as JSON template for import on other instances. */
    public String defaultThresholdsJson() {
        RuleSettings s = new RuleSettings();
        s.thresholds.putAll(RuleSettings.DEFAULT_THRESHOLDS);
        return write(s);
    }

    static List<String> supportedKeys() {
        return List.of("overrides", "thresholds", "excludeNamePatterns", "excludeTags");
    }
}

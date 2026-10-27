package io.twxav2.analysis;

import io.twxav2.ingest.TwxParseResult;
import io.twxav2.model.Finding.Severity;

import java.util.List;

/** SPI for analysis rules: one CDI bean per rule. */
public interface TwxRule {

    String id();          // e.g. "R-SVC-001"

    String title();       // short description for the UI

    /** Catalog metadata (defaults, overridable via rule settings). */
    default String category() { return "Allgemein"; }

    /** Typical severity of this rule for the catalog display. */
    default Severity defaultSeverity() { return Severity.MINOR; }

    /** Impact 1..3; score = severity weight x impact. */
    default int impact() { return 1; }

    /** Maximum findings per rule and snapshot (protection against noise). */
    default int cap() { return Integer.MAX_VALUE; }

    /** false = rule is disabled by default (visible in the catalog, on once
     *  enabled via a rule setting). */
    default boolean defaultEnabled() { return true; }

    /** What is checked and why (catalog documentation). */
    default String rationale() { return ""; }

    /** Remediation hint (catalog documentation). */
    default String guidance() { return ""; }

    /** English catalog texts (i18n); null = the UI falls back to the German text. */
    default String titleEn() { return null; }

    default String categoryEn() { return null; }

    default String rationaleEn() { return null; }

    default String guidanceEn() { return null; }

    default List<Hit> analyze(RuleContext ctx) {
        return analyze(ctx.parsed());
    }

    List<Hit> analyze(TwxParseResult result);

    record Hit(Severity severity, String objectId, String objectName, String message,
               String location, Integer line, String snippet, boolean needsReview, String messageEn) {

        /** Default constructor without enrichment (location/line/snippet/review hint). */
        public Hit(Severity severity, String objectId, String objectName, String message) {
            this(severity, objectId, objectName, message, null, null, null, false, null);
        }

        /** Second-language variant of the default constructor. */
        public Hit(Severity severity, String objectId, String objectName, String message, String messageEn) {
            this(severity, objectId, objectName, message, null, null, null, false, messageEn);
        }

        /** Constructor without a second language (existing code; messageEn = null). */
        public Hit(Severity severity, String objectId, String objectName, String message,
                   String location, Integer line, String snippet, boolean needsReview) {
            this(severity, objectId, objectName, message, location, line, snippet, needsReview, null);
        }
    }
}

// Derived from Dosvak LLC twx-code-analyzer (Apache-2.0); modified for TWX Analyzer v2. See NOTICE.
// Changes: package/rule IDs, false-positive fixes, adapters.
package io.twxav2.analysis.ref.rules;

/** Finding severity; weight drives the ranking. */
public enum Severity {
    CRITICAL(100, "Critical"), MAJOR(40, "Major"), MINOR(10, "Minor"), INFO(2, "Info");
    public final int weight; public final String label;
    Severity(int w, String l) { weight = w; label = l; }
}

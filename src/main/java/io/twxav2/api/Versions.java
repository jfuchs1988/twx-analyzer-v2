package io.twxav2.api;

/**
 * Version comparison for toolkit version names (e.g. "1.10.2" &gt; "1.9.3").
 * Splits into digit/non-digit runs; digit sequences compare numerically,
 * the rest literally (case-insensitive). The same prefix with a digit continuation
 * counts as newer ("1.0.1" &gt; "1.0"); non-digit suffixes ("8.6.0.0_TC") are
 * variants and count as incomparable (rank 0). Unknown versions
 * (null) never win.
 */
final class Versions {

    private Versions() { }

    /** Greater than 0 when a is newer than b; null treated as the oldest value. */
    static int compare(String a, String b) {
        if (a == null || b == null) {
            return a == null ? (b == null ? 0 : -1) : 1;
        }
        if (a.equals(b)) {
            return 0;
        }
        int ia = 0, ib = 0;
        while (ia < a.length() && ib < b.length()) {
            boolean da = Character.isDigit(a.charAt(ia));
            boolean db = Character.isDigit(b.charAt(ib));
            int ea = skipRun(a, ia, da);
            int eb = skipRun(b, ib, db);
            String sa = a.substring(ia, ea);
            String sb = b.substring(ib, eb);
            int c;
            if (da && db) {
                c = new java.math.BigInteger(sa).compareTo(new java.math.BigInteger(sb));
            } else if (da != db) {
                c = da ? -1 : 1; // digit run before letter run
            } else {
                c = sa.compareToIgnoreCase(sb);
            }
            if (c != 0) {
                return c;
            }
            ia = ea;
            ib = eb;
        }
        // same prefix: more runs = newer, but only for a digit continuation
        // ("1.0.1" > "1.0"). Non-digit suffixes ("8.6.0.0_TC", "-SNAPSHOT") are
        // variants, not upgrades -> incomparable (same rank, no "outdated").
        boolean ra = ia < a.length();
        boolean rb = ib < b.length();
        if (ra == rb) {
            return 0;
        }
        String rest = ra ? a.substring(ia) : b.substring(ib);
        int p = 0;
        while (p < rest.length() && !Character.isLetterOrDigit(rest.charAt(p))) {
            p++; // skip separators like ".": "1.0.1" is a digit continuation
        }
        if (p == rest.length() || !Character.isDigit(rest.charAt(p))) {
            return 0;
        }
        return ra ? 1 : -1;
    }

    /** Grouping key for a toolkit: acronym, else name (lowercase). */
    static String key(String acronym, String name) {
        String k = acronym != null && !acronym.isBlank() ? acronym : name;
        return k == null || k.isBlank() ? null : k.toLowerCase();
    }

    private static int skipRun(String s, int from, boolean digits) {
        int i = from;
        while (i < s.length() && Character.isDigit(s.charAt(i)) == digits) {
            i++;
        }
        return i;
    }
}

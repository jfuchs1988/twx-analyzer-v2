package io.twxav2.analysis.ref;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

/**
 * German catalog texts (title/category/description/remediation) for the
 * reference catalog's rules. Maintained in {@code ruletexts-de.properties}
 * (keys {@code R-<KAT>-<nnn>.title/.category/.rationale/.guidance}); the
 * English original text stays in the rule classes and is served as the
 * {@code *En} field. Missing entries fall back to English.
 */
final class RuleTextsDe {

    private static final Properties TEXTS = load();

    private RuleTextsDe() {
    }

    private static Properties load() {
        Properties p = new Properties();
        try (InputStream in = RuleTextsDe.class.getClassLoader()
                .getResourceAsStream("ruletexts-de.properties")) {
            if (in != null) {
                p.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            }
        } catch (IOException e) {
            // without the resource, the rule classes' English original text applies.
        }
        return p;
    }

    /** Text field for a catalog id (R-<KAT>-<nnn>); null when not maintained. */
    static String get(String ruleId, String field) {
        String v = TEXTS.getProperty(ruleId + "." + field);
        return v == null || v.isBlank() ? null : v.trim();
    }

    /** DE text, otherwise the English original text. */
    static String or(String ruleId, String field, String fallback) {
        String v = get(ruleId, field);
        return v != null ? v : fallback;
    }
}

package io.twxav2.service;

import io.twxav2.analysis.RuleSettings;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.List;
import java.util.Locale;

/**
 * Auto-exclusion ("do not check"): objects considered not to check via a
 * name pattern (e.g. {@code _deprecated}) or a Process Center tag.
 * Effective lists = properties (global, via env) unioned with the
 * rule settings (global and per snapshot). Tags are known only from the
 * project manifest (META-INF/metadata.xml) — toolkit objects can only
 * be matched by name.
 */
@ApplicationScoped
public class ExclusionService {

    public static final String SRC_MANUAL = "MANUAL";
    public static final String SRC_NAME = "AUTO_NAME";
    public static final String SRC_TAG = "AUTO_TAG";

    @ConfigProperty(name = "twxav2.exclusion.name-patterns")
    java.util.Optional<List<String>> propertyNamePatterns;

    @ConfigProperty(name = "twxav2.exclusion.tags")
    java.util.Optional<List<String>> propertyTags;

    /** Name patterns: property list + settings (global/snapshot), cleaned. */
    public List<String> namePatterns(RuleSettings s) {
        return RuleSettings.union(RuleSettings.union(filter(propertyNamePatterns.orElse(null)),
                s == null ? null : s.excludeNamePatterns), null);
    }

    /** Tag list: property list + settings (global/snapshot), cleaned. */
    public List<String> tags(RuleSettings s) {
        return RuleSettings.union(RuleSettings.union(filter(propertyTags.orElse(null)),
                s == null ? null : s.excludeTags), null);
    }

    /** Hit source for an object: {source, reason} or null. */
    public String[] match(String name, List<String> objectTags,
                          List<String> patterns, List<String> tagList) {
        String lower = name == null ? "" : name.toLowerCase(Locale.ROOT);
        for (String p : patterns) {
            if (!p.isBlank() && lower.contains(p.toLowerCase(Locale.ROOT))) {
                return new String[]{SRC_NAME, p};
            }
        }
        if (objectTags != null && !objectTags.isEmpty()) {
            for (String t : tagList) {
                for (String have : objectTags) {
                    if (t.equalsIgnoreCase(have)) {
                        return new String[]{SRC_TAG, have};
                    }
                }
            }
        }
        return null;
    }

    private static List<String> filter(List<String> in) {
        if (in == null) {
            return List.of();
        }
        return in.stream().filter(v -> v != null && !v.isBlank()).map(String::trim).toList();
    }
}

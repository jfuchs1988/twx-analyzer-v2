package io.twxav2.service;

import io.twxav2.analysis.RuleSettings;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Auto-exclusion: merged patterns/tags (properties + settings),
 * name-pattern matching is fuzzy (substring, case-insensitive), tag exact.
 */
class ExclusionServiceTest {

    static ExclusionService svc(List<String> namePatterns, List<String> tags) {
        ExclusionService s = new ExclusionService();
        s.propertyNamePatterns = Optional.ofNullable(namePatterns);
        s.propertyTags = Optional.ofNullable(tags);
        return s;
    }

    static RuleSettings settings(List<String> patterns, List<String> tags) {
        RuleSettings s = new RuleSettings();
        s.excludeNamePatterns.addAll(patterns);
        s.excludeTags.addAll(tags);
        return s;
    }

    @Test
    void namesUniteAndCleanup() {
        ExclusionService s = svc(List.of("mockInitData", " _test ", "  "), null);
        assertEquals(List.of("mockInitData", "_test"), s.namePatterns(null));
        assertEquals(List.of("mockInitData", "_test", "_TEMPLATE_CSHS"),
                s.namePatterns(settings(List.of("_TEMPLATE_CSHS", "mockinitdata"), List.of())));
        // UNION without duplicates (case-insensitive), properties first
        assertEquals(List.of(), s.tags(null));
    }

    @Test
    void tagsUnite() {
        ExclusionService s = svc(null, List.of("deprecated", " "));
        assertEquals(List.of("deprecated"), s.tags(null));
        assertEquals(List.of("deprecated", "ALT"), s.tags(settings(List.of(), List.of("ALT"))));
    }

    @Test
    void namestrefferUnscharf() {
        ExclusionService s = svc(null, null);
        String[] hit = s.match("Service mockInitData GV", List.of(), List.of("mockinitdata"), List.of());
        assertArrayEquals(new String[]{ExclusionService.SRC_NAME, "mockinitdata"}, hit);
        assertNull(s.match("Auftrag Start", List.of(), List.of("mockinitdata"), List.of()));
        assertNull(s.match(null, List.of(), List.of("mockinitdata"), List.of()));
        assertNull(s.match("irgendwas", List.of(), List.of(), List.of()));
        assertNull(s.match("irgendwas", List.of(), List.of(" "), List.of())); // empty pattern ignored
    }

    @Test
    void tagHitExactAndCaseInsensitive() {
        ExclusionService s = svc(null, null);
        assertArrayEquals(new String[]{ExclusionService.SRC_TAG, "verworfen"},
                s.match("Auftrag Start", List.of("verworfen"), List.of(), List.of("Verworfen")));
        // exact match, no substring
        assertNull(s.match("x", List.of("deprecated-alt"), List.of(), List.of("deprecated")));
        // name wins over tag
        assertArrayEquals(new String[]{ExclusionService.SRC_NAME, "_test"},
                s.match("a_test", List.of("verworfen"), List.of("_test"), List.of("verworfen")));
    }
}

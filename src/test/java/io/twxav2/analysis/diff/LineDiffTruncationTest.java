package io.twxav2.analysis.diff;

import io.twxav2.analysis.diff.LineDiff.Line;
import io.twxav2.analysis.diff.LineDiff.Result;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Capping paths: change zone beyond MAX_SIDE (note instead of exact diff),
 * output estimate for many change groups (GAP marker "Diff hier
 * begrenzt") and the full-replacement fallback when the edit distance is too large.
 */
class LineDiffTruncationTest {

    @Test
    void changeZoneOverMaxSideBoundedNote() {
        StringBuilder a = new StringBuilder();
        StringBuilder b = new StringBuilder();
        for (int i = 0; i <= LineDiff.MAX_SIDE; i++) {          // 3001 distinct lines
            a.append("alt").append(i).append('\n');
            b.append("neu").append(i).append('\n');
        }
        Result r = LineDiff.diff(a.toString(), b.toString());
        assertTrue(r.truncated());
        assertNotNull(r.note());
        assertTrue(r.deletions() >= LineDiff.MAX_SIDE);
        assertTrue(r.lines().size() <= LineDiff.MAX_OUT + 2, String.valueOf(r.lines().size()));
    }

    @Test
    void manyChangeGroupsCapsOutputWithGapMarker() {
        // 520 groups of 7 lines (6 identical + 1 changed): D = 1040 < MAX_D,
        // op stream stays dense (no hidden context) -> the cap kicks in.
        StringBuilder a = new StringBuilder();
        StringBuilder b = new StringBuilder();
        for (int g = 0; g < 520; g++) {
            for (int j = 0; j < 6; j++) {
                a.append("same").append(g).append('_').append(j).append('\n');
                b.append("same").append(g).append('_').append(j).append('\n');
            }
            a.append("alt").append(g).append('\n');
            b.append("neu").append(g).append('\n');
        }
        Result r = LineDiff.diff(a.toString(), b.toString());
        assertTrue(r.truncated());
        assertTrue(r.lines().size() <= LineDiff.MAX_OUT + 2, String.valueOf(r.lines().size()));
        Line last = r.lines().get(r.lines().size() - 1);
        assertEquals(LineDiff.GAP, last.kind());
        assertTrue(last.text().contains("Diff truncated here"), last.text());
        assertTrue(r.lines().stream().anyMatch(l -> l.kind() == LineDiff.DEL));
    }

    @Test
    void toLargeDistanceRunsToFullExchangeNote() {
        StringBuilder a = new StringBuilder();
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < 1000; i++) {
            a.append("a").append(i).append('\n');
            b.append("b").append(i).append('\n');
        }
        Result r = LineDiff.diff(a.toString(), b.toString());
        assertTrue(r.truncated());
        assertEquals("Too many changes for a line-by-line comparison; "
                + "the changed region was replaced entirely.", r.note());
    }

    @Test
    void oneSidedEdgeWithHeadAndTail() {
        // common head + tail, middle part deleted on one side
        String base = "kopf\nx1\nx2\nm1\nm2\nm3\nschwanz\n";
        String target = "kopf\nx1\nx2\nschwanz\n";
        Result r = LineDiff.diff(base, target);
        assertEquals(3, r.deletions());
        assertEquals(0, r.additions());
        assertFalse(r.truncated());
        assertEquals(3, r.lines().stream().filter(l -> l.kind() == LineDiff.DEL).count());
        assertTrue(r.lines().stream().noneMatch(l -> l.kind() == LineDiff.ADD));
    }
}

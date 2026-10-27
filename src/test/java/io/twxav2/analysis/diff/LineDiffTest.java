package io.twxav2.analysis.diff;

import io.twxav2.analysis.diff.LineDiff.Line;
import io.twxav2.analysis.diff.LineDiff.Result;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Pure algorithmics: Myers diff with head/tail cut, GAP compression, capping. */
class LineDiffTest {

    @Test
    void identischeInhalte() {
        Result r = LineDiff.diff("a\nb", "a\nb");
        assertTrue(r.lines().isEmpty());
        assertEquals(0, r.additions());
        assertEquals(0, r.deletions());
        assertFalse(r.truncated());
        assertEquals("Contents are identical.", r.note());
    }

    @Test
    void replaceOneLineRightInTheMiddle() {
        Result r = LineDiff.diff("a\nb\nc", "a\nB\nc");
        assertEquals(1, r.additions());
        assertEquals(1, r.deletions());
        Line del = r.lines().stream()
                .filter(l -> l.kind() == LineDiff.DEL && l.text().equals("b")).findFirst().orElseThrow();
        assertEquals(2, del.baseLine());
        assertNull(del.targetLine());
        assertTrue(r.lines().stream()
                .anyMatch(l -> l.kind() == LineDiff.ADD && l.text().equals("B")
                        && l.baseLine() == null && l.targetLine() == 2));
        // head/tail are cut before rendering -> no CTX line for "a"
        assertEquals(2, r.lines().size());
    }

    @Test
    void pureInsertion() {
        Result r = LineDiff.diff("a\nb", "a\nx\nb");
        assertEquals(1, r.additions());
        assertEquals(0, r.deletions());
    }

    @Test
    void mixedLineBreaksAreEqual() {
        assertEquals("Contents are identical.", LineDiff.diff("a\r\nb", "a\nb").note());
        assertArrayEquals(new String[] {"a", "b", "c", "d"}, LineDiff.split("a\r\nb\rc\nd"));
        assertArrayEquals(new String[] {"a", ""}, LineDiff.split("a\n"));
        assertEquals(0, LineDiff.split(null).length);
        assertEquals(0, LineDiff.split("").length);
    }

    @Test
    void largeUnchangedBlockIsGap() {
        StringBuilder mid = new StringBuilder();
        for (int i = 0; i < 100; i++) {
            mid.append("same").append(i).append('\n');
        }
        String base = "aender1\n" + mid + "aender2";
        String target = "neu1\n" + mid + "neu2";
        Result r = LineDiff.diff(base, target);
        assertEquals(2, r.additions());
        assertEquals(2, r.deletions());
        assertTrue(r.lines().stream().anyMatch(l -> l.kind() == LineDiff.GAP));
        // 4 changed lines + 6 context lines (3 per side), not 100
        assertTrue(r.lines().size() <= 12, "Zeilen: " + r.lines().size());
    }

    @Test
    void completeExchangeWhenDistanceToLarge() {
        StringBuilder a = new StringBuilder();
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < 3000; i++) {
            a.append("alt").append(i).append('\n');
            b.append("neu").append(i).append('\n');
        }
        Result r = LineDiff.diff(a.toString(), b.toString());
        assertTrue(r.truncated());
        assertNotNull(r.note());
        assertTrue(r.deletions() > 1000);
    }

    @Test
    void allDeletedOrAllNew() {
        Result d = LineDiff.allLines("l1\nl2", true);
        assertEquals(2, d.deletions());
        assertEquals(0, d.additions());
        assertEquals(1, d.lines().get(0).baseLine());
        assertNull(d.lines().get(0).targetLine());
        Result a = LineDiff.allLines("l1", false);
        assertEquals(1, a.additions());
        assertEquals(1, a.lines().get(0).targetLine());
        assertEquals(0, LineDiff.allLines("", true).lines().size());
    }

    @Test
    void oneSidedHugeBoundedOnOutputLines() {
        String big = "x\n".repeat(4000);
        Result a = LineDiff.allLines(big, false);
        assertTrue(a.truncated());
        // "x\n" x4000 -> split produces 4001 lines (incl. empty remainder line)
        assertEquals(4001, a.additions());
        assertTrue(a.lines().stream().anyMatch(l -> l.kind() == LineDiff.GAP));
    }

    @Test
    void veryLongLineIsCaptured() {
        String longLine = "y".repeat(700);
        Result a = LineDiff.allLines(longLine, false);
        assertTrue(a.lines().get(0).text().length() < longLine.length());
    }

    @Test
    void myersAtDistanceOverCapFallsBackToFullCompare() {
        // |n-m| = 1201 > MAX_D (1200): the lower bound exceeds the cap immediately
        String a = "uralt\n";
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < 1202; i++) {
            b.append("neu").append(i).append('\n');
        }
        Result r = LineDiff.diff(a, b.toString());
        assertEquals(1, r.deletions());
        assertEquals(1202, r.additions());
        assertTrue(r.lines().stream().anyMatch(l -> l.kind() == LineDiff.DEL && l.text().equals("uralt")));
    }
}

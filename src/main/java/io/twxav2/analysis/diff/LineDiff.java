package io.twxav2.analysis.diff;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Line-aligned text comparison (B6 detail diff) per Myers O(ND) with a budget.
 * TWX contents arrive line-wise (mixed CR/LF/CRLF - the parser keeps the
 * designer's line endings unchanged), so comparison is line-based: the common
 * head/tail is cut off, the middle runs through the edit graph. The limits
 * are hard so a diff never blows up runtime or memory: page size, edit
 * distance, output lines.
 */
public final class LineDiff {

    /** Kind of an output line. */
    public static final int CTX = 0;
    public static final int DEL = 1;
    public static final int ADD = 2;
    /** compressed context omission */
    public static final int GAP = 3;

    /** Maximum lines compared per side (after head/tail cut). */
    static final int MAX_SIDE = 3000;
    /** Maximum tolerated edit distance (D), otherwise full-replacement fallback. */
    static final int MAX_D = 1200;
    /** Context lines around each change. */
    static final int CONTEXT = 3;
    /** Maximum delivered lines incl. GAP markers. */
    static final int MAX_OUT = 3000;
    /** Line-text cap in characters. */
    static final int MAX_CHARS = 400;

    /**
     * A diff line: kind (CTX/DEL/ADD/GAP), line numbers (null per side when
     * the line is missing there) and text.
     */
    public record Line(int kind, Integer baseLine, Integer targetLine, String text) { }

    /** Result: lines to display + counts over the entire difference. */
    public record Result(List<Line> lines, int additions, int deletions,
                         boolean truncated, String note) { }

    private LineDiff() { }

    /** Diff the lines; the caller handles null contents (diff only with text). */
    public static Result diff(String baseContent, String targetContent) {
        String[] a = split(baseContent);
        String[] b = split(targetContent);
        boolean truncated = false;
        String note = null;

        // common head and tail
        int head = 0;
        while (head < a.length && head < b.length && a[head].equals(b[head])) {
            head++;
        }
        int tail = 0;
        while (tail < a.length - head && tail < b.length - head
                && a[a.length - 1 - tail].equals(b[b.length - 1 - tail])) {
            tail++;
        }
        int midA = a.length - head - tail;
        int midB = b.length - head - tail;
        if (midA == 0 && midB == 0) {
            return new Result(List.of(), 0, 0, false, "Contents are identical.");
        }
        if (midA > MAX_SIDE || midB > MAX_SIDE) {
            midA = Math.min(midA, MAX_SIDE);
            midB = Math.min(midB, MAX_SIDE);
            truncated = true;
            note = "Diff limited to the first " + MAX_SIDE + " lines of the changed region.";
        }
        String[] ma = Arrays.copyOfRange(a, head, head + midA);
        String[] mb = Arrays.copyOfRange(b, head, head + midB);

        // edit operations: 0=same, 1=deleted (base only), 2=inserted (target only)
        List<Byte> ops = myers(ma, mb);
        boolean replaced = false;
        if (ops == null) {
            ops = new ArrayList<>(midA + midB);
            for (int i = 0; i < midA; i++) {
                ops.add((byte) 1);
            }
            for (int i = 0; i < midB; i++) {
                ops.add((byte) 2);
            }
            replaced = true;
        }

        int additions = 0;
        int deletions = 0;
        for (byte op : ops) {
            if (op == 1) {
                deletions++;
            } else if (op == 2) {
                additions++;
            }
        }

        boolean[] outTrunc = new boolean[1];
        List<Line> lines = render(ops, ma, mb, head, outTrunc);
        truncated |= replaced || outTrunc[0];
        if (replaced) {
            note = "Too many changes for a line-by-line comparison; "
                    + "the changed region was replaced entirely.";
        }
        return new Result(lines, additions, deletions, truncated, note);
    }

    /** Splits at each line break (CR, LF, CRLF); as the export delivers them. */
    static String[] split(String content) {
        if (content == null || content.isEmpty()) {
            return new String[0];
        }
        return content.split("\\R", -1);
    }

    /**
     * Entire content as a one-sided diff (an object exists on only one side):
     * all lines deleted or inserted, same caps as diff().
     */
    public static Result allLines(String content, boolean asDeletions) {
        String[] s = split(content);
        int shown = Math.min(s.length, MAX_OUT - 1);
        boolean truncated = s.length > shown;
        List<Line> lines = new ArrayList<>(shown + 1);
        for (int i = 0; i < shown; i++) {
            lines.add(asDeletions
                    ? new Line(DEL, i + 1, null, cap(s[i]))
                    : new Line(ADD, null, i + 1, cap(s[i])));
        }
        if (truncated) {
            lines.add(new Line(GAP, null, null, "Content truncated here (maximum " + MAX_OUT + " lines)"));
        }
        return new Result(lines, asDeletions ? 0 : s.length, asDeletions ? s.length : 0,
                truncated, null);
    }

    /**
     * Myers greedy pass; returns the edit operations via backward trace
     * or null when the edit distance exceeds MAX_D (caller falls back).
     */
    private static List<Byte> myers(String[] a, String[] b) {
        int n = a.length, m = b.length;
        if (Math.abs(n - m) > MAX_D) {
            return null; // |n-m| is the lower bound for D
        }
        int cap = Math.min(n + m, MAX_D);
        int off = cap + 1;                     // index offset for k in [-cap-1, cap+1]
        int[] v = new int[2 * off + 3];
        List<int[]> trace = new ArrayList<>(); // round d stores the window BEFORE the pass
        v[off + 1] = 0;
        int foundD = -1;
        outer:
        for (int d = 0; d <= cap; d++) {
            trace.add(Arrays.copyOfRange(v, off - d - 1, off + d + 2)); // window k in [-d-1, d+1]
            for (int k = -d; k <= d; k += 2) {
                int x;
                if (k == -d || (k != d && v[off + k - 1] < v[off + k + 1])) {
                    x = v[off + k + 1];        // entered from above: insertion
                } else {
                    x = v[off + k - 1] + 1;    // from the left: delete
                }
                int y = x - k;
                while (x < n && y < m && a[x].equals(b[y])) {
                    x++;
                    y++;
                }
                v[off + k] = x;
                if (x >= n && y >= m) {
                    foundD = d;
                    break outer;
                }
            }
        }
        if (foundD < 0) {
            return null;
        }

        // backwards through the rounds: first the snake (equal lines), then the move
        List<Byte> rev = new ArrayList<>();
        int x = n, y = m;
        for (int d = foundD; d >= 1; d--) {
            int[] vd = trace.get(d);           // window for k in [-d-1, d+1], index k+d+1
            int k = x - y;
            int prevK;
            if (k == -d || (k != d && at(vd, d, k - 1) < at(vd, d, k + 1))) {
                prevK = k + 1;
            } else {
                prevK = k - 1;
            }
            int prevX = at(vd, d, prevK);
            int prevY = prevX - prevK;
            while (x > prevX && y > prevY) {
                rev.add((byte) 0);
                x--;
                y--;
            }
            rev.add(prevK == k - 1 ? (byte) 1 : (byte) 2);
            x = prevX;
            y = prevY;
        }
        while (x > 0 && y > 0) {               // D0: pure snake from the origin
            rev.add((byte) 0);
            x--;
            y--;
        }
        List<Byte> ops = new ArrayList<>(rev.size());
        for (int i = rev.size() - 1; i >= 0; i--) {
            ops.add(rev.get(i));
        }
        return ops;
    }

    private static int at(int[] window, int d, int k) {
        return window[k + d + 1];
    }

    /**
     * Turn edit operations into display lines: compress context
     * (CONTEXT lines around each change), assign line numbers, apply the cap.
     * The counts in the Result still count the entire difference.
     */
    private static List<Line> render(List<Byte> ops, String[] a, String[] b, int head,
                                     boolean[] outTruncated) {
        boolean[] keep = new boolean[ops.size()];
        for (int i = 0; i < ops.size(); i++) {
            if (ops.get(i) == 0) {
                continue;
            }
            for (int j = Math.max(0, i - CONTEXT); j <= Math.min(ops.size() - 1, i + CONTEXT); j++) {
                keep[j] = true;
            }
        }
        List<Line> lines = new ArrayList<>();
        int ai = 0; // index in a (head lines already subtracted, number = head + ai + 1)
        int bi = 0;
        int skipped = 0;
        int truncatedAt = -1;
        for (int i = 0; i < ops.size(); i++) {
            byte op = ops.get(i);
            if (op == 0) {
                if (!keep[i]) {
                    skipped++;
                    ai++;
                    bi++;
                    continue;
                }
                if (skipped > 0) {
                    if (lines.size() >= MAX_OUT) {
                        truncatedAt = skipped;
                        break;
                    }
                    lines.add(new Line(GAP, null, null, skipped + " identical lines omitted"));
                    skipped = 0;
                }
                if (lines.size() >= MAX_OUT) {
                    truncatedAt = -2;
                    break;
                }
                lines.add(new Line(CTX, head + ai + 1, head + bi + 1, cap(a[ai])));
                ai++;
                bi++;
            } else if (op == 1) {
                if (lines.size() >= MAX_OUT) {
                    truncatedAt = -2;
                    break;
                }
                if (skipped > 0) {
                    lines.add(new Line(GAP, null, null, skipped + " identical lines omitted"));
                    skipped = 0;
                }
                lines.add(new Line(DEL, head + ai + 1, null, cap(a[ai])));
                ai++;
            } else {
                if (lines.size() >= MAX_OUT) {
                    truncatedAt = -2;
                    break;
                }
                if (skipped > 0) {
                    lines.add(new Line(GAP, null, null, skipped + " identical lines omitted"));
                    skipped = 0;
                }
                lines.add(new Line(ADD, null, head + bi + 1, cap(b[bi])));
                bi++;
            }
        }
        if (skipped > 0 && lines.size() < MAX_OUT) {
            lines.add(new Line(GAP, null, null, skipped + " identical lines omitted"));
        }
        if (truncatedAt != -1) {
            outTruncated[0] = true;
            // truncatedAt > 0 = additionally hidden context lines, -2 = hit the cap
            String extra = truncatedAt > 0
                    ? " (plus " + truncatedAt + " identical context lines omitted)" : "";
            lines.add(new Line(GAP, null, null, "Diff truncated here (maximum " + MAX_OUT + " lines)" + extra));
        }
        return lines;
    }

    private static String cap(String s) {
        return s.length() <= MAX_CHARS ? s : s.substring(0, MAX_CHARS) + "…";
    }
}

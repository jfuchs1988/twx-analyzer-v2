package io.twxav2.service;

import io.twxav2.model.Snapshot;
import io.twxav2.model.VariableUsage;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds BAW variable accesses (tw.local.x, tw.processVariables.x, tw.variables.x,
 * tw.parameters.x, tw.inputs.x, tw.outputs.x) line-exact in the object content.
 * tw.system.* and rubbing-edge objects are deliberately not captured (no user data).
 * Assignment = identifier followed by = / += / -= … (==, ===, >=, != count as read access).
 */
public final class VariableScanner {

    private static final Pattern USE = Pattern.compile(
            "tw\\.(local|processVariables|variables|parameters|inputs|outputs)\\.([A-Za-z_$][A-Za-z0-9_$]*)");
    /** Rest of the line after the identifier: assignment operator, but no comparison. */
    private static final Pattern ASSIGN = Pattern.compile(
            "^\\s*(?:[-+*/%&|^]|>>>?|<<<?)?=(?![=>])");
    /** Limit lines per object (endless generators/minified scripts). */
    private static final int MAX_ROWS_PER_OBJECT = 2000;

    private VariableScanner() { }

    public static void scan(Snapshot s, String objectId, String objectName, String prefix,
                            String content, List<VariableUsage> out) {
        if (content == null || content.indexOf("tw.") < 0) {
            return;
        }
        int before = out.size();
        String[] lines = content.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            Matcher m = USE.matcher(lines[i]);
            while (m.find()) {
                VariableUsage v = new VariableUsage();
                v.snapshot = s;
                v.objectId = objectId;
                v.objectName = objectName;
                v.prefix = prefix;
                v.scope = m.group(1);
                v.variable = m.group(2);
                v.assign = ASSIGN.matcher(lines[i].substring(m.end())).find();
                v.line = i + 1;
                v.lineText = excerpt(lines[i], m.start(), m.end());
                out.add(v);
                if (out.size() - before >= MAX_ROWS_PER_OBJECT) {
                    return;
                }
            }
        }
    }

    /** Window excerpt around the hit — processXML lines can be hundreds of KB. */
    private static String excerpt(String line, int start, int end) {
        int from = Math.max(0, start - 120);
        int to = Math.min(line.length(), end + 200);
        String s = line.substring(from, to).strip();
        if (from > 0) {
            s = "…" + s;
        }
        if (to < line.length()) {
            s = s + "…";
        }
        return s.length() <= 398 ? s : s.substring(0, 398) + "…";
    }
}

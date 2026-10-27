package io.twxav2.analysis.script;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.twxav2.ingest.TwxParseResult.ParsedObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Helper for pattern/AST rules: extracts JavaScript fragments from objects
 * and provides a version without comments and string literals (positions are
 * preserved 1:1, replaced by spaces) so regex patterns do not match inside
 * comments or text snippets.
 */
public final class ScriptScanner {

    /** Object prefixes whose XML contains script blocks (process, BPD, coach view). */
    private static final Set<String> SCRIPT_PREFIXES = Set.of("1", "25", "64");

    /**
     * Real &lt;script&gt; blocks. The lookahead is mandatory: without it the pattern
     * also matches {@code <scriptId>}/{@code <scriptTypeId>} (TWComponent blocks) and
     * the garbage fragments swallow the following real script block up to its
     * &lt;/script&gt; — it would then be invisible to all rules.
     * Equally mandatory: the self-closing exclusion — empty scripts appear as
     * {@code <script isNull="true" />} in the XML; without the exclusion they become
     * an opening block that swallows the following XML (guid/versionId) up to the
     * next real &lt;/script&gt; — R-JS-022 then reports "missing ; before
     * statement" on raw-text garbage instead of JavaScript.
     */
    private static final Pattern SCRIPT_BLOCK =
            Pattern.compile("<script(?=[\\s>])(?![^>]*/>)[^>]*>(.*?)</script>",
                    Pattern.DOTALL | Pattern.CASE_INSENSITIVE);

    private static final Pattern SCRIPT_NAME =
            Pattern.compile("(?i)\\bname\\s*=\\s*\"([^\"]*)\"");

    /** JS files as object content (managed assets / server files). */
    private static final Pattern JS_FILE = Pattern.compile("(?i)\\.js$");

    /** Prefixes whose &lt;jsonData&gt; carries the BPMN model JSON with script fields. */
    private static final Set<String> MODEL_JSON_PREFIXES = Set.of("1", "25");

    private static final Pattern JSON_DATA =
            Pattern.compile("<jsonData>(.*?)</jsonData>", Pattern.DOTALL);

    /** BPMN-JSON field name -> field-type label for the fragment description (order = output order, deterministic). */
    private static final Map<String, String> MODEL_JS_KEYS;
    static {
        Map<String, String> keys = new java.util.LinkedHashMap<>();
        keys.put("preScript", "Pre-script");
        keys.put("postScript", "Post-script");
        keys.put("script", "Script");
        keys.put("conditionExpression", "Condition");
        keys.put("transitionScript", "Transition script");
        MODEL_JS_KEYS = Collections.unmodifiableMap(keys);
    }

    private static final ObjectMapper MODEL_MAPPER = new ObjectMapper();

    /** Fragments per object (read jointly by ~145 rules; content is immutable). */
    private static final Map<ParsedObject, List<Fragment>> FRAGMENT_CACHE =
            Collections.synchronizedMap(new WeakHashMap<>());


    /**
     * A script fragment with its location in the object.
     *
     * @param description   human-readable location ("script block 2", "file")
     * @param js            the JS text
     * @param lineOffset    line in which the fragment starts within the object (1-based)
     * @param fromAttribute fragment comes from an XML attribute (condition/assignment)
     */
    public record Fragment(String description, String js, int lineOffset, boolean fromAttribute) { }

    /** A string literal with its start offset within the fragment. */
    public record Literal(String text, int start) { }

    /**
     * JS fragments of an object: &lt;script&gt; blocks in process/BPD/coach objects,
     * plus the script fields of the BPMN model JSON (&lt;jsonData&gt; of prefixes
     * 1/25: script/conditionExpression/preScript/postScript/transitionScript, unless
     * identical in content to a preceding script block), raw text for JS files.
     */
    public static List<Fragment> fragments(ParsedObject o) {
        if (o.content() == null) {
            return List.of();
        }
        List<Fragment> cached = FRAGMENT_CACHE.get(o);
        if (cached != null) {
            return cached;
        }
        List<Fragment> computed = List.copyOf(computeFragments(o));
        FRAGMENT_CACHE.put(o, computed);
        return computed;
    }

    private static List<Fragment> computeFragments(ParsedObject o) {
        List<Fragment> out = new ArrayList<>();
        if ("61".equals(o.prefix()) && JS_FILE.matcher(nvl(o.name())).find()) {
            out.add(new Fragment("File", o.content(), 1, false));
            return out;
        }
        if (!SCRIPT_PREFIXES.contains(o.prefix())) {
            return out;
        }
        String content = o.content();
        Matcher m = SCRIPT_BLOCK.matcher(content);
        int block = 0;
        while (m.find()) {
            block++;
            String raw = m.group(1);
            int line = 1 + countNewlines(content, m.start(1));
            out.add(new Fragment("Script block " + block + scriptLabel(content, m.group()),
                    unescape(clean(raw)), line, false));
        }
        if (MODEL_JSON_PREFIXES.contains(o.prefix())) {
            addModelFragments(content, out);
        }
        return out;
    }

    /**
     * Script fields from the BPMN model JSON (prefix 1/25): tasks keep their script
     * here, gateway conditions exclusively — the DOM &lt;script&gt; blocks do not
     * cover that. Content-identical
     * duplicates of the DOM blocks are skipped; for non-JS formats
     * (text/plain, XSLT) the DOM block is removed as well (otherwise
     * false R-JS-022 findings on data blocks).
     */
    private static void addModelFragments(String content, List<Fragment> out) {
        Matcher jd = JSON_DATA.matcher(content);
        if (!jd.find()) {
            return;
        }
        JsonNode root;
        try {
            root = MODEL_MAPPER.readTree(unescape(jd.group(1)));
        } catch (Exception e) {
            return; // broken model JSON: R-JS-022 is responsible for that, stay silent here
        }
        int line = 1 + countNewlines(content, jd.start());
        Set<String> seen = new HashSet<>();
        for (Fragment f : out) {
            seen.add(canon(f.js()));
        }
        walkModel(root, null, line, seen, out);
    }

    /** Recurses through the model JSON; neighboring name= values serve the fragment description. */
    private static void walkModel(JsonNode node, String taskName, int line,
                                  Set<String> seen, List<Fragment> out) {
        if (node == null) {
            return;
        }
        if (node.isArray()) {
            for (JsonNode child : node) {
                walkModel(child, taskName, line, seen, out);
            }
            return;
        }
        if (!node.isObject()) {
            return;
        }
        JsonNode nameNode = node.get("name");
        final String name = nameNode != null && nameNode.isTextual() && !nameNode.asText().isBlank()
                ? nameNode.asText().trim() : taskName;
        for (Map.Entry<String, String> key : MODEL_JS_KEYS.entrySet()) {
            JsonNode field = node.get(key.getKey());
            if (field == null || !field.isObject()) {
                continue;
            }
            JsonNode format = node.get("scriptFormat");
            boolean nonJs = format != null && format.isTextual()
                    && !format.asText().toLowerCase().contains("javascript");
            JsonNode content = field.get("content");
            if (content == null || !content.isArray() || content.isEmpty()
                    || !content.get(0).isTextual()) {
                continue;
            }
            String js = clean(content.get(0).asText());
            if (nonJs) {
                // Not JS (text/plain data blocks, XSLT, ...): also drop the
                // content-identical DOM <script> block — otherwise R-JS-022 parses
                // data as code (known case: text/plain tasks with identical <script> block).
                if (!js.isEmpty()) {
                    String c = canon(js);
                    out.removeIf(f -> canon(f.js()).equals(c));
                }
                continue;
            }
            if (js.isEmpty() || !seen.add(canon(js))) {
                continue;
            }
            String label = "BPMN-JSON " + key.getValue()
                    + (name != null ? " (" + name + ")" : "");
            out.add(new Fragment(label, js, line, false));
        }
        node.fields().forEachRemaining(e -> walkModel(e.getValue(), name, line, seen, out));
    }

    /** Comparison form for duplicate check JSON vs. DOM block (whitespace ignored). */
    private static String canon(String js) {
        StringBuilder sb = new StringBuilder(js.length());
        for (int i = 0; i < js.length(); i++) {
            char c = js.charAt(i);
            if (c == '\r' || c == '\n' || c == '\t') {
                c = ' ';
            }
            if (c != ' ') {
                sb.append(c);
            }
        }
        return sb.toString();
    }


    /** name= attribute of the <script> start tag as a suffix (" (change)"); empty if none. */
    private static String scriptLabel(String content, String openMatch) {
        String tag = openMatch.substring(0, Math.min(openMatch.length(), openMatch.indexOf('>') + 1));
        Matcher nm = SCRIPT_NAME.matcher(tag);
        return nm.find() ? " (" + nm.group(1).trim() + ")" : "";
    }

    /** Unpack XML entities: TWX scripts are entity-encoded (&amp;#xD; instead of CR, &amp;lt; etc.). */
    public static String unescape(String s) {
        // Check all five named entities in the guard: if one was missing (historically &gt),
        // e.g. "&gt=" stays in the BPMN-JSON fragment and R-JS-022 reports "missing )".
        if (s.indexOf("&lt") < 0 && s.indexOf("&gt") < 0 && s.indexOf("&amp;") < 0
                && s.indexOf("&#") < 0 && s.indexOf("&quot;") < 0 && s.indexOf("&apos;") < 0) {
            return s;
        }
        StringBuilder sb = new StringBuilder(s.length());
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '&') {
                int semi = s.indexOf(';', i);
                if (semi > i && semi - i <= 10) {
                    String ent = s.substring(i + 1, semi);
                    switch (ent) {
                        case "lt" -> { sb.append('<'); i = semi + 1; continue; }
                        case "gt" -> { sb.append('>'); i = semi + 1; continue; }
                        case "amp" -> { sb.append('&'); i = semi + 1; continue; }
                        case "quot" -> { sb.append('"'); i = semi + 1; continue; }
                        case "apos" -> { sb.append('\''); i = semi + 1; continue; }
                        default -> { }
                    }
                    if (ent.charAt(0) == '#') {
                        try {
                            int cp = ent.charAt(1) == 'x' || ent.charAt(1) == 'X'
                                    ? Integer.parseInt(ent.substring(2), 16)
                                    : Integer.parseInt(ent.substring(1));
                            sb.append(Character.toChars(cp));
                            i = semi + 1;
                            continue;
                        } catch (RuntimeException e) {
                            // treat as plain text
                        }
                    }
                }
            }
            sb.append(c);
            i++;
        }
        return sb.toString();
    }

    /** true if the object can provide script fragments at all. */
    public static boolean mayContainScripts(ParsedObject o) {
        return o.content() != null
                && (SCRIPT_PREFIXES.contains(o.prefix())
                || ("61".equals(o.prefix()) && JS_FILE.matcher(nvl(o.name())).find()));
    }

    /** Replace comments and string literals with spaces; length/line count stay the same. */
    public static String strip(String js) {
        char[] c = js.toCharArray();
        int n = c.length;
        int i = 0;
        while (i < n) {
            char ch = c[i];
            if (ch == '/' && i + 1 < n && c[i + 1] == '/') {
                while (i < n && c[i] != '\n' && c[i] != '\r') {
                    c[i++] = ' ';
                }
            } else if (ch == '/' && i + 1 < n && c[i + 1] == '*') {
                int j = i;
                while (j < n && !(c[j] == '*' && j + 1 < n && c[j + 1] == '/')) {
                    if (c[j] != '\n' && c[j] != '\r') {
                        c[j] = ' ';
                    }
                    j++;
                }
                if (j < n) {
                    c[j++] = ' ';
                    if (j < n) {
                        c[j++] = ' ';
                    }
                }
                i = j;
            } else if (ch == '\'' || ch == '"') {
                int j = scanString(c, i, ch, n);
                boolean closed = j < n && c[j] == ch;
                blank(c, i, Math.min(j, n - 1), closed);
                i = closed ? j + 1 : n;
            } else if (ch == '`') {
                int j = scanTemplate(c, i, n);
                boolean closed = j < n && c[j] == '`';
                blank(c, i, Math.min(j, n - 1), closed);
                i = closed ? j + 1 : n;
            } else if (ch == '/' && i + 1 < n && regexAllowed(c, i)) {
                int j = i + 1;
                boolean cls = false;
                while (j < n && (c[j] != '/' || cls)) {
                    if (c[j] == '\\') {
                        j += 2;
                        continue;
                    }
                    if (c[j] == '[') {
                        cls = true;
                    } else if (c[j] == ']') {
                        cls = false;
                    }
                    if (c[j] == '\n' || c[j] == '\r') {
                        break;   // aborted regex: not a literal
                    }
                    j++;
                }
                if (j < n && c[j] == '/' && !cls) {
                    for (int k = i; k <= j; k++) {
                        c[k] = ' ';
                    }
                    i = j + 1;
                } else {
                    i++;   // was apparently a division
                }
            } else {
                i++;
            }
        }
        return new String(c);
    }

    /** String literals of a fragment, with text (incl. quotes) and offset. */
    public static List<Literal> stringLiterals(String js) {
        List<Literal> out = new ArrayList<>();
        char[] c = js.toCharArray();
        int n = c.length;
        int i = 0;
        while (i < n) {
            char ch = c[i];
            if (ch == '/' && i + 1 < n && c[i + 1] == '/') {
                while (i < n && c[i] != '\n' && c[i] != '\r') {
                    i++;
                }
            } else if (ch == '/' && i + 1 < n && c[i + 1] == '*') {
                int j = js.indexOf("*/", i + 2);
                i = j < 0 ? n : j + 2;
            } else if (ch == '\'' || ch == '"' || ch == '`') {
                int j = ch == '`' ? scanTemplate(c, i, n) : scanString(c, i, ch, n);
                int end = Math.min(j, n - 1);
                out.add(new Literal(js.substring(i, end + 1), i));
                i = j < n ? j + 1 : n;
            } else {
                i++;
            }
        }
        return out;
    }

    /** Line (1-based, relative to the fragment) of an offset; CR, LF and CRLF each count as 1. */
    public static int lineAt(String js, int offset) {
        int line = 1;
        for (int i = 0; i < Math.min(offset, js.length()); i++) {
            char c = js.charAt(i);
            if (c == '\n') {
                line++;
            } else if (c == '\r' && i + 1 < js.length() && js.charAt(i + 1) != '\n') {
                line++;
            }
        }
        return line;
    }

    /** Single-line finding around an offset (max. 180 characters). */
    public static String snippetAt(String js, int offset) {
        int from = js.lastIndexOf('\n', Math.max(0, offset - 1)) + 1;
        int cr = js.lastIndexOf('\r', Math.max(0, offset - 1)) + 1;
        from = Math.max(from, cr);
        int to = js.indexOf('\n', offset);
        int toCr = js.indexOf('\r', offset);
        if (to < 0) {
            to = toCr < 0 ? js.length() : toCr;
        } else if (toCr >= 0 && toCr < to) {
            to = toCr;
        }
        String line = js.substring(from, Math.min(to, js.length())).trim();
        if (line.length() > 180) {
            int cut = Math.min(offset - from, line.length() - 1);
            int start = Math.max(0, cut - 80);
            line = (start > 0 ? "…" : "") + line.substring(start, Math.min(line.length(), start + 180));
        }
        return line;
    }

    /** Complete line (1-based, CR/LF/CRLF) of a fragment, max. 180 characters. */
    public static String lineText(String js, int lineNumber) {
        int pos = 0;
        for (int l = 1; l < lineNumber && pos < js.length(); l++) {
            while (pos < js.length() && js.charAt(pos) != '\r' && js.charAt(pos) != '\n') {
                pos++;
            }
            if (pos >= js.length()) {
                break;
            }
            pos += (js.charAt(pos) == '\r' && pos + 1 < js.length() && js.charAt(pos + 1) == '\n') ? 2 : 1;
        }
        return pos >= js.length() ? "" : snippetAt(js, pos);
    }

    /** Black out script-block contents: only the XML skeleton remains (mappings, declarations). */
    public static String maskScripts(String content) {
        return SCRIPT_BLOCK.matcher(content).replaceAll(m -> " ");
    }

    /** Remove CDATA wrappers and whitespace (as in previous rule practice). */
    public static String clean(String raw) {
        String s = raw.trim();
        if (s.startsWith("<![CDATA[")) {
            s = s.substring(9);
        }
        if (s.endsWith("]]>")) {
            s = s.substring(0, s.length() - 3);
        }
        return s.trim();
    }

    // ---------- internals ----------

    /** Scan end: index of the closing quote character, or n. */
    private static int scanString(char[] c, int start, char q, int n) {
        int j = start + 1;
        while (j < n) {
            if (c[j] == '\\') {
                j += 2;
                continue;
            }
            if (c[j] == q || c[j] == '\n' || c[j] == '\r') {
                return j;
            }
            j++;
        }
        return n;
    }

    /** Scan template literals; ${...} expressions are NOT treated as code (rough approximation). */
    private static int scanTemplate(char[] c, int start, int n) {
        int j = start + 1;
        while (j < n) {
            if (c[j] == '\\') {
                j += 2;
                continue;
            }
            if (c[j] == '`') {
                return j;
            }
            j++;
        }
        return n;
    }

    private static void blank(char[] c, int from, int to, boolean keepQuotes) {
        int f = keepQuotes ? from + 1 : from;
        int t = keepQuotes ? to : to + 1;
        for (int i = Math.max(f, 0); i < Math.min(t, c.length); i++) {
            if (c[i] != '\n' && c[i] != '\r') {
                c[i] = ' ';
            }
        }
    }

    /** May a regex literal start at position i (division vs. regex heuristic)? */
    private static boolean regexAllowed(char[] c, int i) {
        for (int j = i - 1; j >= 0; j--) {
            char p = c[j];
            if (p == ' ' || p == '\t' || p == '\n' || p == '\r') {
                continue;
            }
            return !Character.isLetterOrDigit(p) && p != '_' && p != '$'
                    && p != ')' && p != ']' && p != '}' && p != '"' && p != '\'' && p != '`';
        }
        return true;
    }

    private static int countNewlines(String s, int end) {
        int n = 0;
        for (int i = 0; i < end; i++) {
            if (s.charAt(i) == '\n') {
                n++;
            }
        }
        return n;
    }

    private static String nvl(String s) {
        return s == null ? "" : s;
    }

    private ScriptScanner() { }
}

package io.twxav2.analysis.types;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.twxav2.analysis.script.ScriptScanner;
import io.twxav2.ingest.TwxParseResult;
import io.twxav2.ingest.TwxParseResult.ParsedObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Type model (B2i): which tw.local variables carry a complex type (Business Data
 * Type, prefix 12) and which member types that type has.
 *
 * Declaration path: {@code <processVariable>/<processParameter name=…>} with
 * {@code <classId>…/12.uuid</classId>} in the same block (prefix 1/25; in BPDs
 * everything is entity-encoded, so unescape before scanning). Member path:
 * XSD-like jsonData of the 12.x objects — local part of the type QName
 * ({@code {lombardi}String} → String) as the type, {@code refid="12.x"} in
 * otherAttributes as complex reference. Only the top member level (exactly the
 * one {@code tw.local.x.member} hits). Loads exactly once per parse result
 * (cache like BusinessData/CoachViews).
 */
public final class VariableTypes {

    /** Member/literal kinds; "other" = present but undecidable for the rules. */
    public static final String STR = "str";
    public static final String NUM = "num";
    public static final String BOOL = "bool";
    public static final String DATE = "date";
    public static final String COMPLEX = "complex";
    public static final String OTHER = "other";

    /** A literal assignment to tw.local — member null = assignment to the whole object. */
    public record Assign(String var, String member, String kind, int line,
                         String where, String raw) { }

    private static final Pattern VAR_DECL =
            Pattern.compile("<(?:processVariable|processParameter)\\b[^>]*name=\"([^\"]+)\"");
    private static final Pattern CLASS_REF =
            Pattern.compile("<classId\\b[^>]*>[^<]*?/12\\.([0-9a-fA-F\\-]{36})\\s*</classId>");
    private static final Pattern JSON_DATA =
            Pattern.compile("<jsonData>(.*?)</jsonData>", Pattern.DOTALL);
    private static final Pattern REF_ATTR =
            Pattern.compile("12\\.[0-9a-fA-F\\-]{36}");

    private static final Map<Identity, VariableTypes> CACHE = new WeakHashMap<>();
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private record Identity(TwxParseResult ref) {
        @Override
        public boolean equals(Object o) {
            return o instanceof Identity i && i.ref == ref;
        }

        @Override
        public int hashCode() {
            return System.identityHashCode(ref);
        }
    }

    public static synchronized VariableTypes of(TwxParseResult parsed) {
        return CACHE.computeIfAbsent(new Identity(parsed), id -> new VariableTypes(parsed));
    }

    private final List<ParsedObject> classObjects = new ArrayList<>();
    private final Map<String, Map<String, String>> membersByClass = new HashMap<>();
    private final Map<ParsedObject, Map<String, String>> varsByObject = new HashMap<>();

    private VariableTypes(TwxParseResult parsed) {
        for (ParsedObject o : parsed.objects()) {
            if ("12".equals(o.prefix())) {
                classObjects.add(o);
            }
        }
    }

    /** Name → member kind of the business data type; empty if type unknown/without members. */
    public Map<String, String> members(String classUuid) {
        return membersByClass.computeIfAbsent(classUuid, this::loadMembers);
    }

    /** Name → class UUID of all complex-type variables of the object (declarations cast). */
    public Map<String, String> complexVars(ParsedObject o) {
        return varsByObject.computeIfAbsent(o, this::loadDecls);
    }

    /**
     * All literal assignments to tw.local in the object. The scan runs over the
     * raw text (the rules need the literals themselves); findings that are
     * blanked out in the stripped text (comment/string interiors) drop
     * out — positions are identical in both versions.
     * Hand-written linear scan instead of regex: the regex pattern for
     * string literals recurses per character and tips the stack on mega-literals
     * (observed live: StackOverflowError on a 37,883-line
     * stack-trace string in a service script).
     */
    public List<Assign> assignments(ParsedObject o) {
        List<Assign> out = new ArrayList<>();
        for (ScriptScanner.Fragment f : ScriptScanner.fragments(o)) {
            if (f.js() == null) {
                continue;
            }
            String raw = f.js();
            String stripped = ScriptScanner.strip(raw);
            int n = raw.length();
            int p = 0;
            while ((p = raw.indexOf("tw.local.", p)) >= 0) {
                int q = p + 9;                       // "tw.local.".length()
                int vEnd = wordEnd(raw, q);
                if (vEnd == q) {
                    p = q;
                    continue;
                }
                String var = raw.substring(q, vEnd);
                String member = null;
                int eq = -1;
                // Greedy like the regex: try a member, otherwise none; "=" directly
                // (no ==, no += — += would have spaces/digits before it)
                int mEnd = vEnd < n && raw.charAt(vEnd) == '.' ? wordEnd(raw, vEnd + 1) : vEnd;
                if (mEnd > vEnd + 1) {
                    int cand = skipWs(raw, mEnd, n);
                    if (cand < n && raw.charAt(cand) == '=' && !at(raw, cand + 1, '=')) {
                        member = raw.substring(vEnd + 1, mEnd);
                        eq = cand;
                    }
                }
                if (eq < 0) {
                    member = null;
                    int cand = skipWs(raw, vEnd, n);
                    if (!(cand < n && raw.charAt(cand) == '=' && !at(raw, cand + 1, '='))) {
                        p = q;
                        continue;
                    }
                    eq = cand;
                }
                int lit = skipWs(raw, eq + 1, n);
                int litEnd = lit < n ? literalEnd(raw, lit) : -1;
                // Lookahead (?![\w.]): word char or dot after the literal => no assignment
                if (litEnd < 0 || (litEnd < n && (isWordChar(raw.charAt(litEnd)) || raw.charAt(litEnd) == '.'))) {
                    p = q;
                    continue;
                }
                if (stripped.charAt(p) == ' ') {
                    // match was inside a comment or string
                    p = litEnd;                      // skip the literal
                    continue;
                }
                int line = f.lineOffset() + ScriptScanner.lineAt(raw, p) - 1;
                out.add(new Assign(var, member, literalKind(raw.substring(lit, litEnd)),
                        line, f.description(), raw.substring(p, litEnd)));
                p = litEnd;                          // never rescan literal interiors
            }
        }
        return out;
    }

    /** Index of the next non-space from i on (i itself allowed), n at the end. */
    private static int skipWs(String s, int i, int n) {
        while (i < n && Character.isWhitespace(s.charAt(i))) {
            i++;
        }
        return i;
    }

    private static boolean at(String s, int i, char c) {
        return i < s.length() && s.charAt(i) == c;
    }

    /** End of the word-char run from i on (var/member names); ASCII like \w in the regex. */
    private static int wordEnd(String s, int i) {
        int n = s.length();
        while (i < n && isWordChar(s.charAt(i))) {
            i++;
        }
        return i;
    }

    /**
     * Linear end (exclusive) of the string/number/bool literal from index i,
     * -1 if none. Matches the ASSIGN alternatives: strings end before carriage
     * returns (no multiline), escapes \x are passed through.
     */
    private static int literalEnd(String s, int i) {
        int n = s.length();
        if (i < 0 || i >= n) {
            return -1;
        }
        char c = s.charAt(i);
        if (c == '"' || c == '\'') {
            int j = i + 1;
            while (j < n) {
                char d = s.charAt(j);
                if (d == '\r' || d == '\n') {
                    return -1;
                }
                if (d == '\\') {
                    j += 2;
                    continue;
                }
                if (d == c) {
                    return j + 1;
                }
                j++;
            }
            return -1;
        }
        int j = i;
        if (j < n && (c == '-' || (c >= '0' && c <= '9'))) {
            if (c == '-') {
                j++;
            }
            int digits = j;
            while (j < n && s.charAt(j) >= '0' && s.charAt(j) <= '9') {
                j++;
            }
            if (j == digits) {
                return -1;
            }
            if (j + 1 < n && s.charAt(j) == '.'
                    && s.charAt(j + 1) >= '0' && s.charAt(j + 1) <= '9') {
                j++;
                while (j < n && s.charAt(j) >= '0' && s.charAt(j) <= '9') {
                    j++;
                }
            }
            return j;
        }
        if (s.startsWith("true", i) && !isWordChar(chAt(s, i + 4))) {
            return i + 4;
        }
        if (s.startsWith("false", i) && !isWordChar(chAt(s, i + 5))) {
            return i + 5;
        }
        return -1;
    }

    private static char chAt(String s, int i) {
        return i < s.length() ? s.charAt(i) : '\0';
    }

    private static boolean isWordChar(char c) {
        return c == '_' || (c >= '0' && c <= '9')
                || (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
    }

    /** Kind of a literal text (str/num/bool), otherwise "other". */
    public static String literalKind(String literal) {
        char c = literal.charAt(0);
        if (c == '"' || c == '\'') {
            return STR;
        }
        if (c == '-' || (c >= '0' && c <= '9')) {
            return NUM;
        }
        if ("true".equals(literal) || "false".equals(literal)) {
            return BOOL;
        }
        return OTHER;
    }

    private Map<String, String> loadDecls(ParsedObject o) {
        Map<String, String> out = new LinkedHashMap<>();
        if (o.content() == null
                || !("1".equals(o.prefix()) || "25".equals(o.prefix()))) {
            return out;
        }
        String text = ScriptScanner.unescape(o.content());
        Matcher m = VAR_DECL.matcher(text);
        while (m.find()) {
            String name = m.group(1);
            int blockStart = text.indexOf('>', m.end() - 1) + 1;
            int blockEnd = nextBlockEnd(text, m.end());
            if (blockStart <= 0 || blockEnd <= blockStart) {
                continue;
            }
            Matcher c = CLASS_REF.matcher(text.substring(blockStart,
                    Math.min(blockEnd, blockStart + 4000)));
            if (c.find()) {
                out.put(name, c.group(1));
            }
        }
        return out;
    }

    /** Block boundary: end of the next variable declaration (otherwise document end). */
    private static int nextBlockEnd(String text, int from) {
        Matcher next = VAR_DECL.matcher(text);
        return next.find(from) ? next.start() : text.length();
    }

    private Map<String, String> loadMembers(String uuid) {
        Map<String, String> out = new LinkedHashMap<>();
        String json = classJson(uuid);
        if (json == null) {
            return out;
        }
        JsonNode root;
        try {
            root = MAPPER.readTree(json);
        } catch (Exception e) {
            return out;
        }
        if (root == null || !root.isObject()) {
            return out;
        }
        // top element level: complexType/sequence/element (also wrapped via
        // element; complexType and sequence occur as object OR list)
        List<JsonNode> tops = new ArrayList<>();
        for (JsonNode ct : listify(root.path("complexType"))) {
            addSequenceElements(ct.path("sequence"), tops);
        }
        for (JsonNode el : listify(root.path("element"))) {
            for (JsonNode ct : listify(el.path("complexType"))) {
                addSequenceElements(ct.path("sequence"), tops);
            }
        }
        for (JsonNode el : tops) {
            String name = el.path("name").isTextual() ? el.get("name").asText() : null;
            if (name == null || name.isEmpty()) {
                continue;
            }
            out.putIfAbsent(name, memberKind(el));
        }
        return out;
    }

    /** sequence (object or list) → collect its element nodes. */
    private static void addSequenceElements(JsonNode seq, List<JsonNode> out) {
        for (JsonNode s : listify(seq)) {
            addElements(s.path("element"), out);
        }
    }

    private static void addElements(JsonNode node, List<JsonNode> out) {
        if (node.isArray()) {
            node.forEach(out::add);
        } else if (node.isObject() && !node.isEmpty()) {
            out.add(node);
        }
    }

    private static List<JsonNode> listify(JsonNode v) {
        List<JsonNode> l = new ArrayList<>();
        if (v.isArray()) {
            v.forEach(l::add);
        } else if (v.isObject() && !v.isEmpty()) {
            l.add(v);
        }
        return l;
    }

    private static String memberKind(JsonNode el) {
        // QName forms: {ns}String (Jackson-XmlType) or ns:String or String.
        // Warning, live finding: simple members additionally carry an
        // otherAttributes-refid on their type PO — the type attribute value counts.
        String type = el.path("type").isTextual() ? el.get("type").asText() : "";
        if (type.isEmpty()) {
            return REF_ATTR.matcher(el.path("otherAttributes").toString()).find()
                    ? COMPLEX : OTHER;
        }
        int sep = Math.max(type.lastIndexOf('}'),
                Math.max(type.lastIndexOf('#'), type.lastIndexOf(':')));
        String local = type.substring(sep + 1);
        return switch (local.toLowerCase()) {
            case "string" -> STR;
            case "boolean" -> BOOL;
            case "int", "integer", "long", "short", "decimal", "float", "double",
                    "positiveinteger", "nonnegativeinteger", "nonpositiveinteger",
                    "negativeinteger" -> NUM;
            case "datetime", "date", "time" -> DATE;
            default -> OTHER;
        };
    }

    private String classJson(String uuid) {
        for (ParsedObject o : classObjects) {
            if (o.id().equals("12." + uuid) && o.content() != null) {
                Matcher m = JSON_DATA.matcher(o.content());
                if (m.find()) {
                    return ScriptScanner.unescape(m.group(1));
                }
                return null;
            }
        }
        return null;
    }
}

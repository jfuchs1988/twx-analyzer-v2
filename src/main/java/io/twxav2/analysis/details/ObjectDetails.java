package io.twxav2.analysis.details;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.twxav2.analysis.script.ScriptScanner;
import io.twxav2.ingest.TwxParseResult.ParsedObject;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;

/**
 * Opposite view for the artifact browser (B7): per object type the most important
 * model properties as tables (declarations, steps, flow objects,
 * attributes, coach options, team members, script blocks) instead of raw text only.
 * Works on the content of ONE object (on demand, no snapshot cache);
 * DOM accesses follow the same paths as Services/BpdModels so that copies in the
 * bpmn2Model raw block (B2g pitfall) are not counted twice
 */
public final class ObjectDetails {

    /** One table of the detail view (title, column headers, rows as text). */
    public record Section(String title, List<String> columns, List<List<String>> rows) {

        static Section of(String title, List<String> columns, List<List<String>> rows) {
            return new Section(title, columns, rows);
        }

        private static List<String> row(String... cells) {
            return List.of(cells);
        }
    }

    private static final int MAX_ROWS = 200;

    private ObjectDetails() { }

    /** Detail sections per prefix; empty list when nothing is readable. */
    public static List<Section> sections(ParsedObject o) {
        List<Section> out = new ArrayList<>();
        if (o.content() == null) {
            return out;
        }
        try {
            switch (o.prefix()) {
                case "1" -> serviceSections(o, out);
                case "25" -> bpdSections(o, out);
                case "12" -> twClassSections(o, out);
                case "64" -> coachViewSections(o, out);
                case "24" -> teamSections(o, out);
                default -> { }
            }
        } catch (Exception ignored) {
            // unreadable object: whatever was collected up to here counts
        }
        if (ScriptScanner.mayContainScripts(o)) {
            scriptSection(o, out);
        }
        return out;
    }

    // ---------- process/service (prefix 1) ----------

    private static void serviceSections(ParsedObject o, List<Section> out) throws Exception {
        Document tw = dom(o.content());
        Element proc = child(tw.getDocumentElement(), "process");
        if (proc == null) {
            return;
        }
        List<List<String>> vars = new ArrayList<>();
        List<List<String>> steps = new ArrayList<>();
        List<List<String>> links = new ArrayList<>();
        int prePosts = 0;
        for (Element c : children(proc)) {
            switch (c.getTagName()) {
                case "processVariable" -> vars.add(Section.row(c.getAttribute("name"),
                        "true".equals(text(c, "hasDefault")) ? "yes" : ""));
                case "item" -> {
                    steps.add(Section.row(nvl(text(c, "name")), nvl(text(c, "tWComponentName"))));
                    if (hasChild(c, "processPrePosts")) {
                        prePosts++;
                    }
                }
                case "link" -> links.add(Section.row(nvl(c.getAttribute("name"))));
                default -> { }
            }
        }
        List<List<String>> varsRows = cap(vars);
        if (!varsRows.isEmpty()) {
            out.add(Section.of("Declarations (tw.local)", List.of("Name", "Default value"), varsRows));
        }
        if (!steps.isEmpty()) {
            out.add(Section.of("Steps", List.of("Name", "Component"), cap(steps)));
        }
        if (!links.isEmpty()) {
            out.add(Section.of("Transitions", List.of("Name"), cap(links)));
        }
        out.add(Section.of("Overview", List.of("Property", "Value"), List.of(
                Section.row("Process type", nvl(text(proc, "processType"))),
                Section.row("Pre/post scripts", String.valueOf(prePosts)),
                Section.row("Declarations", String.valueOf(vars.size())),
                Section.row("Steps", String.valueOf(steps.size())))));
    }

    // ---------- BPD (prefix 25) ----------

    private static void bpdSections(ParsedObject o, List<Section> out) throws Exception {
        Document tw = dom(o.content());
        Element bpd = child(tw.getDocumentElement(), "bpd");
        if (bpd == null) {
            return;
        }
        List<List<String>> flowObjects = new ArrayList<>();
        for (Element e : children(bpd)) {
            if (!local(e).equals("flowObject")) {
                continue;
            }
            String loop = nvl(text(e, "loopType"));
            flowObjects.add(Section.row(
                    nvl(nvl(attr(e, "name"), text(e, "name"))), // both sources missing -> ""
                    nvl(attr(e, "componentType")),
                    loop.isEmpty() || "0".equals(loop) ? "" : "Multi-instance",
                    "true".equalsIgnoreCase(text(e, "isHidden")) ? "yes" : ""));
            if (flowObjects.size() >= MAX_ROWS) {
                break;
            }
        }
        int searchable = countDeep(bpd, "searchableField");
        out.add(Section.of("Overview", List.of("Property", "Value"), List.of(
                Section.row("Flow objects", String.valueOf(flowObjects.size())),
                Section.row("Searchable Fields", String.valueOf(searchable)),
                Section.row("Auto-Tracking",
                        "true".equalsIgnoreCase(firstDeepText(bpd, "autoTrackingEnabled", 0))
                                ? "on" : "off"))));
        if (!flowObjects.isEmpty()) {
            out.add(Section.of("Flow objects",
                    List.of("Name", "Type", "MI", "Hidden"), flowObjects));
        }
    }

    // ---------- Business Data Type (prefix 12) ----------

    private static void twClassSections(ParsedObject o, List<Section> out) {
        String json = modelJson(o.content());
        if (json == null) {
            return;
        }
        JsonNode root;
        try {
            root = new ObjectMapper().readTree(json);
        } catch (Exception e) {
            return;
        }
        boolean wrapper = root.has("rootElement");
        boolean imported = root.path("targetNamespace").asText("").matches(
                "(?is).*(externalservice|swagger).*");
        List<List<String>> props = new ArrayList<>();
        collectProps(root, props);
        String doc = documentation(root);
        out.add(Section.of("Type summary", List.of("Property", "Value"), List.of(
                Section.row("Kind", wrapper ? "Wrapper (message)"
                        : imported ? "Import (service spec)" : "Hand-authored"),
                Section.row("Root documentation", doc == null ? "none" : "present"),
                Section.row("Attributes (flattened)", String.valueOf(props.size())))));
        if (!props.isEmpty()) {
            out.add(Section.of("Attributes",
                    List.of("Name", "Type", "Documentation"), props));
        }
    }

    /** XSD-like JSON: collects (name, type, doc yes/no) up to MAX_ROWS, at all depths. */
    private static void collectProps(JsonNode node, List<List<String>> out) {
        if (out.size() >= MAX_ROWS || node == null) {
            return;
        }
        if (node.isArray()) {
            for (JsonNode c : node) {
                collectProps(c, out);
            }
            return;
        }
        if (!node.isObject()) {
            return;
        }
        String name = node.path("name").asText(null);
        String type = node.path("type").asText(null);
        if (name != null && node.has("type")) {
            String doc = documentation(node);
            out.add(Section.row(name, nvl(type), doc == null ? "" : "yes"));
        }
        node.fields().forEachRemaining(e -> collectProps(e.getValue(), out));
    }

    /** Documentation text in TWX format: annotation.documentation=[{content:[text]}]. */
    private static String documentation(JsonNode node) {
        JsonNode doc = node.path("annotation").path("documentation");
        if (doc.isMissingNode() || doc.isNull()) {
            return null;
        }
        JsonNode first = doc.isArray() ? doc.path(0) : doc;
        JsonNode content = first.path("content");
        String text;
        if (content.isArray() && !content.isEmpty()) {
            text = content.get(0).asText("");
        } else {
            text = content.asText(first.asText(""));
        }
        return text.isBlank() ? null : text.trim();
    }

    // ---------- coach view (prefix 64) ----------

    private static void coachViewSections(ParsedObject o, List<Section> out) throws Exception {
        Document tw = dom(o.content());
        List<List<String>> options = new ArrayList<>();
        var opts = tw.getElementsByTagName("configOption");
        for (int i = 0; i < opts.getLength() && options.size() < MAX_ROWS; i++) {
            if (opts.item(i) instanceof Element el) {
                String label = el.getAttribute("label");
                options.add(Section.row(nvl(el.getAttribute("name")), nvl(label),
                        label == null || label.isBlank() ? "missing" : ""));
            }
        }
        List<List<String>> bindings = new ArrayList<>();
        var bts = tw.getElementsByTagName("bindingType");
        for (int i = 0; i < bts.getLength() && bindings.size() < MAX_ROWS; i++) {
            if (bts.item(i) instanceof Element el) {
                String name = el.getAttribute("name");
                bindings.add(Section.row(name == null || name.isBlank() ? el.getTextContent().trim() : name));
            }
        }
        if (!options.isEmpty()) {
            out.add(Section.of("Config options", List.of("Name", "Label", "Note"), options));
        }
        if (!bindings.isEmpty()) {
            out.add(Section.of("Binding types", List.of("Name"), bindings));
        }
    }

    // ---------- team (prefix 24) ----------

    private static void teamSections(ParsedObject o, List<Section> out) {
        String json = modelJson(o.content());
        if (json == null) {
            return;
        }
        try {
            Document tw = dom(o.content());
            Element part = child(tw.getDocumentElement(), "participant");
            String teamJson = part == null ? null : text(part, "jsonData");
            if (teamJson == null) {
                return;
            }
            JsonNode root = new ObjectMapper().readTree(teamJson);
            List<List<String>> members = new ArrayList<>();
            collectMembers(root, "Users", "Individual user", members);
            collectMembers(root, "UserGroups", "Group", members);
            if (!members.isEmpty()) {
                out.add(Section.of("Members", List.of("Name", "Type"), members));
            }
        } catch (Exception ignored) {
        }
    }

    private static void collectMembers(JsonNode node, String field, String kind,
                                       List<List<String>> out) {
        if (node == null || out.size() >= MAX_ROWS) {
            return;
        }
        if (node.isArray()) {
            for (JsonNode c : node) {
                collectMembers(c, field, kind, out);
            }
            return;
        }
        if (!node.isObject()) {
            return;
        }
        node.fields().forEachRemaining(e -> {
            if (e.getKey().equals(field) && e.getValue().isArray()) {
                for (JsonNode m : e.getValue()) {
                    if (out.size() >= MAX_ROWS) {
                        return;
                    }
                    String name = m.isTextual() ? m.asText() : m.path("name").asText(null);
                    if (name == null || name.isBlank()) {
                        name = m.path("user").asText(m.path("displayName").asText("?"));
                    }
                    out.add(Section.row(name, kind));
                }
            } else {
                collectMembers(e.getValue(), field, kind, out);
            }
        });
    }

    // ---------- script blocks (all script prefixes) ----------

    private static void scriptSection(ParsedObject o, List<Section> out) {
        List<List<String>> rows = new ArrayList<>();
        int n = 1;
        for (var f : ScriptScanner.fragments(o)) {
            rows.add(Section.row(String.valueOf(n++), f.description(),
                    String.valueOf(countLines(f.js()))));
            if (rows.size() >= MAX_ROWS) {
                break;
            }
        }
        if (!rows.isEmpty()) {
            out.add(Section.of("Script blocks", List.of("#", "Location", "Lines"), rows));
        }
    }

    // ---------- tooling (DOM patterns like Services/BpdModels) ----------

    private static String modelJson(String content) {
        try {
            Document tw = dom(content);
            var jd = tw.getElementsByTagName("jsonData");
            for (int i = 0; i < jd.getLength(); i++) {
                String t = jd.item(i).getTextContent();
                if (t != null && !t.isBlank()) {
                    return t.trim();
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static Document dom(String xml) throws Exception {
        var f = DocumentBuilderFactory.newDefaultInstance();
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        f.setNamespaceAware(false);
        return f.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
    }

    private static String local(Element e) {
        String t = e.getTagName();
        int i = t.indexOf(':');
        return i >= 0 ? t.substring(i + 1) : t;
    }

    private static List<Element> children(Element e) {
        List<Element> out = new ArrayList<>();
        for (var n = e.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element el) {
                out.add(el);
            }
        }
        return out;
    }

    private static Element child(Element e, String localName) {
        for (Element c : children(e)) {
            if (local(c).equals(localName)) {
                return c;
            }
        }
        return null;
    }

    private static boolean hasChild(Element e, String localName) {
        return child(e, localName) != null;
    }

    private static Element deepChild(Element e, String localName, int depth) {
        if (depth > 8) {
            return null;
        }
        for (Element c : children(e)) {
            if (local(c).equals(localName)) {
                return c;
            }
            Element hit = deepChild(c, localName, depth + 1);
            if (hit != null) {
                return hit;
            }
        }
        return null;
    }

    private static String firstDeepText(Element e, String localName, int depth) {
        Element hit = deepChild(e, localName, depth);
        return hit == null ? null : hit.getTextContent();
    }

    private static int countDeep(Element e, String localName) {
        int n = local(e).equals(localName) ? 1 : 0;
        for (Element c : children(e)) {
            n += countDeep(c, localName);
        }
        return n;
    }

    private static String text(Element parent, String localName) {
        Element c = parent == null ? null : child(parent, localName);
        if (c == null || "true".equals(c.getAttribute("isNull"))) {
            return null;
        }
        String t = c.getTextContent();
        return t == null ? null : t.trim();
    }

    private static String attr(Element e, String name) {
        String v = e.getAttribute(name);
        return v.isEmpty() ? null : v;
    }

    private static String nvl(String s) {
        return nvl(s, "");
    }

    private static String nvl(String s, String fallback) {
        return s == null ? fallback : s;
    }

    /** row truncation for tables. */
    private static List<List<String>> cap(List<List<String>> rows) {
        return rows.size() <= MAX_ROWS ? rows : rows.subList(0, MAX_ROWS);
    }

    /** lines of a script (CR/CRLF/LF like ScriptScanner). */
    private static int countLines(String s) {
        int lines = 1;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\r') {
                lines++;
            } else if (c == '\n' && (i == 0 || s.charAt(i - 1) != '\r')) {
                lines++;
            }
        }
        return lines;
    }
}

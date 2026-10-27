package io.twxav2.analysis.services;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.twxav2.ingest.TwxParseResult;
import io.twxav2.ingest.TwxParseResult.ParsedObject;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import org.xml.sax.InputSource;

/**
 * Service side of the processes (B2g): the process/service objects (prefix 1) with
 * their declarations (processVariable), steps (item), transitions (link) and
 * pre/post scripts, plus the teams (prefix 24) with their static member lists.
 * Loads exactly once per parse result (cache), since many rules read the same model.
 */
public final class Services {

    /** Declaration (tw.local) of a service/process. */
    public record Var(String name, boolean hasDefault) { }

    /** Step (FlowObject item) of a service/process. */
    public record Step(String name, String component) { }

    /** A process/service object (prefix 1) as an analyzable summary. */
    public record Service(String id, String name, String description, int processType,
                          List<Var> vars, List<Step> steps, List<String> linkNames,
                          List<Integer> prePostScriptLines) { }

    /** Team (prefix 24) with the count of hard-coded individual users. */
    public record Team(String id, String name, int users, int groups) { }

    /** Overall result per snapshot. */
    public record Model(List<Service> services, List<Team> teams) { }

    // Identity semantics like BpdModels: TwxParseResult is a large record.
    private static final Map<Identity, Model> CACHE = new WeakHashMap<>();

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

    private Services() { }

    /** Snapshot's model; loads and caches on first call. */
    public static synchronized Model of(TwxParseResult parsed) {
        Model cached = CACHE.get(new Identity(parsed));
        if (cached == null) {
            List<Service> services = new ArrayList<>();
            List<Team> teams = new ArrayList<>();
            for (ParsedObject o : parsed.objects()) {
                if (o.content() == null) {
                    continue; // parser gap >=1 MB: object not inspectable
                }
                try {
                    if ("1".equals(o.prefix())) {
                        Service s = parseService(o);
                        if (s != null) {
                            services.add(s);
                        }
                    } else if ("24".equals(o.prefix())) {
                        Team t = parseTeam(o);
                        if (t != null) {
                            teams.add(t);
                        }
                    }
                } catch (Exception ignored) {
                    // broken object XML: nonexistent for the catalog rules (R-JS-022 shows it)
                }
            }
            cached = new Model(List.copyOf(services), List.copyOf(teams));
            CACHE.put(new Identity(parsed), cached);
        }
        return cached;
    }

    // ---------- Service/process (prefix 1) ----------

    private static Service parseService(ParsedObject o) throws Exception {
        Document tw = dom(o.content());
        Element proc = child(tw.getDocumentElement(), "process");
        if (proc == null) {
            return null;
        }
        int processType = parseInt(text(proc, "processType"));
        List<Var> vars = new ArrayList<>();
        List<Step> steps = new ArrayList<>();
        List<String> links = new ArrayList<>();
        List<Integer> prePostLines = new ArrayList<>();
        for (Element c : children(proc)) {
            switch (c.getTagName()) {
                case "processVariable" -> vars.add(new Var(c.getAttribute("name"),
                        "true".equals(text(c, "hasDefault"))));
                case "link" -> links.add(orEmpty(c.getAttribute("name")));
                case "item" -> {
                    steps.add(new Step(orEmpty(text(c, "name")), orEmpty(text(c, "tWComponentName"))));
                    for (Element pp : children(c)) {
                        if ("processPrePosts".equals(pp.getTagName())) {
                            String script = text(pp, "script");
                            if (script != null && !script.isBlank()) {
                                prePostLines.add(countLines(script));
                            }
                        }
                    }
                }
                default -> { }
            }
        }
        Element desc = directChild(proc, "description");
        String description = desc == null || "true".equals(desc.getAttribute("isNull"))
                ? "" : orEmpty(desc.getTextContent()).trim();
        String name = orEmpty(proc.getAttribute("name"));
        return new Service(o.id(), name, description, processType,
                List.copyOf(vars), List.copyOf(steps), List.copyOf(links),
                List.copyOf(prePostLines));
    }

    // ---------- Team (prefix 24) ----------

    private static Team parseTeam(ParsedObject o) {
        try {
            Document tw = dom(o.content());
            Element part = child(tw.getDocumentElement(), "participant");
            if (part == null) {
                return null;
            }
            int users = 0;
            int groups = 0;
            String json = text(part, "jsonData");
            if (json != null && !json.isBlank()) {
                JsonNode root = new ObjectMapper().readTree(json);
                users = sumArrays(root, "Users");
                groups = sumArrays(root, "UserGroups");
            }
            return new Team(o.id(), orEmpty(part.getAttribute("name")), users, groups);
        } catch (Exception e) {
            return null;
        }
    }

    /** Sum of the lengths of all field arrays with the given key (any depth). */
    private static int sumArrays(JsonNode node, String field) {
        int n = 0;
        if (node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> it = node.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> e = it.next();
                if (e.getKey().equals(field) && e.getValue().isArray()) {
                    n += e.getValue().size();
                } else {
                    n += sumArrays(e.getValue(), field);
                }
            }
        } else if (node.isArray()) {
            for (JsonNode c : node) {
                n += sumArrays(c, field);
            }
        }
        return n;
    }

    // ---------- DOM utilities ----------

    private static Document dom(String xml) throws Exception {
        var f = DocumentBuilderFactory.newDefaultInstance();
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        f.setNamespaceAware(false);
        return f.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
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

    private static Element child(Element e, String tag) {
        return directChild(e, tag);
    }

    private static Element directChild(Element e, String tag) {
        for (Element c : children(e)) {
            if (c.getTagName().equals(tag)) {
                return c;
            }
        }
        return null;
    }

    private static String text(Element e, String tag) {
        Element c = directChild(e, tag);
        return c == null || "true".equals(c.getAttribute("isNull")) ? null : c.getTextContent();
    }

    private static String orEmpty(String s) {
        return s == null ? "" : s.trim();
    }

    private static int parseInt(String s) {
        try {
            return s == null ? -1 : Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** Lines of a script; TWX scripts deliver CR-only, so count like ScriptScanner. */
    static int countLines(String script) {
        int lines = 1;
        for (int i = 0; i < script.length(); i++) {
            char c = script.charAt(i);
            if (c == '\r') {
                lines++;
            } else if (c == '\n') {
                if (i == 0 || script.charAt(i - 1) != '\r') {
                    lines++;
                }
            }
        }
        return lines;
    }
}

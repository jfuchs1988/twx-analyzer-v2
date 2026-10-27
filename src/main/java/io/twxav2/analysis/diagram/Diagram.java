package io.twxav2.analysis.diagram;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the designer coordinates from the BPMN model JSON (&lt;jsonData&gt; of
 * prefixes 1/25): every element with extensionElements.nodeVisualInfo carries
 * x/y/width/height, edges (declaredType sequenceFlow) carry sourceRef/targetRef
 * plus port hints from linkVisualInfo. A TWX export contains no BPMN-DI
 * segment points — edges are drawn as straight lines between the ports.
 * Loads on demand per object (no snapshot cache needed).
 * Lanes (swim lanes) are not in the model JSON but in the BPMN XML of the
 * bpmn2Data section: each &lt;lane&gt; element with nodeVisualInfo carries its
 * band geometry, flowNodeRef children the associated node IDs.
 */
public final class Diagram {

    /** Model JSON in the object XML (may be entity-encoded, as in ScriptScanner). */
    private static final Pattern JSON_DATA =
            Pattern.compile("<jsonData>(.*?)</jsonData>", Pattern.DOTALL);
    private static final Pattern ENTITY = Pattern.compile("&#(\\d+);|&quot;|&apos;|&lt;|&gt;|&amp;");

    /** Lane opening in the BPMN-XML (any prefix); isSystemLane separates them from */
    /** the geometry-less TWX service lanes (those carry systemLane as a child element). */
    private static final Pattern LANE_OPEN =
            Pattern.compile("<(?:[\\w.-]+:)?lane\\s[^>]*isSystemLane[^>]*>");
    private static final Pattern LANE_NAME = Pattern.compile("\\bname=\"([^\"]*)\"");
    private static final Pattern LANE_SYS = Pattern.compile("isSystemLane=\"([^\"]*)\"");
    /** Lane geometry: first nodeVisualInfo after the lane opening. */
    private static final Pattern LANE_VIS =
            Pattern.compile("<(?:[\\w.-]+:)?nodeVisualInfo\\b[^>]*>");
    private static final Pattern REF_ATTR = Pattern.compile("\\b(x|y|width|height|color)=\"([^\"]*)\"");
    private static final Pattern LANE_REF =
            Pattern.compile("<(?:[\\w.-]+:)?flowNodeRef>\\s*([^<\\s]+)\\s*</(?:[\\w.-]+:)?flowNodeRef>");

    public record Node(String id, String kind, String name, double x, double y,
                       double width, double height, String color) { }

    public record Edge(String id, String name, String source, String target,
                       String sourcePort, String targetPort, String condition) { }

    /** Swim lane with band geometry (designer coordinates, same source as nodes). */
    public record Lane(String id, String name, double x, double y,
                       double width, double height, String color,
                       boolean systemLane, List<String> nodeIds) { }

    public record Model(List<Node> nodes, List<Edge> edges, List<Lane> lanes) {
        public boolean isEmpty() {
            return nodes.isEmpty();
        }
    }

    private Diagram() { }

    /** Diagram from object content; empty model if no model JSON/coordinates are present. */
    public static Model of(String content) {
        if (content == null) {
            return new Model(List.of(), List.of(), List.of());
        }
        // Like ScriptScanner: first unpack entity encoding (TWX models may be
        // entirely entity-encoded), then cut out the model JSON.
        String plain = unescape(content);
        Matcher jd = JSON_DATA.matcher(plain);
        List<Node> nodes = new ArrayList<>();
        List<Edge> edges = new ArrayList<>();
        if (jd.find()) {
            JsonNode root;
            try {
                root = new ObjectMapper().readTree(jd.group(1));
            } catch (Exception e) {
                return new Model(List.of(), List.of(), lanes(plain)); // broken JSON: maybe still lanes
            }
            walk(root, null, nodes, edges);
        }
        List<Lane> lanes = lanes(plain);
        // Remove lane nodes from the model JSON: service flows carry their
        // system lane with declaredType "lane" and nodeVisualInfo — drawn as a
        // node it would lie as a semi-transparent area rectangle over the
        // entire model ("veil"). Lanes are drawn separately as bands;
        // the same ID in both sources is therefore a duplicate.
        Set<String> laneIds = new HashSet<>();
        for (Lane l : lanes) {
            if (l.id() != null) {
                laneIds.add(l.id());
            }
        }
        nodes.removeIf(n -> "lane".equalsIgnoreCase(n.kind()) || laneIds.contains(n.id()));
        return new Model(List.copyOf(nodes), List.copyOf(edges), lanes);
    }

    /**
     * Lanes from the BPMN-XML (bpmn2Data). Segment per lane = opening up to the
     * next lane opening; within it the first nodeVisualInfo (band geometry) and
     * all flowNodeRef nodes. Lanes without geometry or entirely without a
     * flowNodeRef band (pure sorting lanes) are skipped.
     */
    static List<Lane> lanes(String plain) {
        List<int[]> opens = new ArrayList<>();
        List<String> tags = new ArrayList<>();
        Matcher m = LANE_OPEN.matcher(plain);
        while (m.find()) {
            opens.add(new int[] { m.start(), m.end() });
            tags.add(m.group());
        }
        if (opens.isEmpty()) {
            return List.of();
        }
        List<Lane> out = new ArrayList<>();
        for (int i = 0; i < opens.size(); i++) {
            String tag = tags.get(i);
            int from = opens.get(i)[1];
            int to = i + 1 < opens.size() ? opens.get(i + 1)[0] : Math.min(plain.length(), from + 20_000);
            String seg = plain.substring(from, to);
            Matcher vis = LANE_VIS.matcher(seg);
            if (!vis.find()) {
                continue; // no geometry (TWX page or lane without layout)
            }
            double x = 0, y = 0, w = 0, h = 0;
            String color = null;
            Matcher at = REF_ATTR.matcher(vis.group());
            while (at.find()) {
                switch (at.group(1)) {
                    case "x" -> x = num(at.group(2));
                    case "y" -> y = num(at.group(2));
                    case "width" -> w = num(at.group(2));
                    case "height" -> h = num(at.group(2));
                    case "color" -> color = blankToNull(at.group(2));
                    default -> { }
                }
            }
            if (w <= 0 || h <= 0) {
                continue;
            }
            Matcher nm = LANE_NAME.matcher(tag);
            String id = null;
            Matcher idm = Pattern.compile("\\bid=\"([^\"]+)\"").matcher(tag);
            if (idm.find()) {
                id = idm.group(1);
            }
            boolean sys = false;
            Matcher sysm = LANE_SYS.matcher(tag);
            if (sysm.find()) {
                sys = "true".equalsIgnoreCase(sysm.group(1));
            }
            List<String> refs = new ArrayList<>();
            Matcher rf = LANE_REF.matcher(seg);
            while (rf.find() && refs.size() < 4000) {
                refs.add(rf.group(1));
            }
            out.add(new Lane(id, nm.find() ? blankToNull(nm.group(1)) : null,
                    x, y, w, h, color, sys, List.copyOf(refs)));
        }
        return List.copyOf(out);
    }

    private static double num(String s) {
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static void walk(JsonNode node, String name, List<Node> nodes, List<Edge> edges) {
        if (node == null) {
            return;
        }
        if (node.isArray()) {
            for (JsonNode c : node) {
                walk(c, name, nodes, edges);
            }
            return;
        }
        if (!node.isObject()) {
            return;
        }
        String declaredType = text(node, "declaredType");
        String id = text(node, "id");
        String nodeName = text(node, "name") != null ? text(node, "name") : name;
        JsonNode ext = node.get("extensionElements");
        if (id != null && declaredType != null && ext != null) {
            JsonNode vis = firstObject(ext.get("nodeVisualInfo"));
            if (vis != null) {
                nodes.add(new Node(id, declaredType, nodeName,
                        vis.path("x").asDouble(0), vis.path("y").asDouble(0),
                        vis.path("width").asDouble(90), vis.path("height").asDouble(60),
                        blankToNull(vis.path("color").asText(""))));
            }
            JsonNode link = firstObject(ext.get("linkVisualInfo"));
            if (link != null) {
                String source = text(node, "sourceRef");
                String target = text(node, "targetRef");
                if (source != null && target != null) {
                    edges.add(new Edge(id, nodeName, source, target,
                            blankToNull(link.path("sourcePortLocation").asText("")),
                            blankToNull(link.path("targetPortLocation").asText("")),
                            conditionOf(node)));
                }
            }
        }
        node.fields().forEachRemaining(e -> walk(e.getValue(), nodeName, nodes, edges));
    }

    /** nodeVisualInfo/linkVisualInfo occur as an object or a list. */
    private static JsonNode firstObject(JsonNode n) {
        if (n == null) {
            return null;
        }
        if (n.isObject()) {
            return n;
        }
        if (n.isArray() && !n.isEmpty() && n.get(0).isObject()) {
            return n.get(0);
        }
        return null;
    }

    /** Condition of an edge as short text (content array in the JSON field). */
    private static String conditionOf(JsonNode flow) {
        JsonNode cond = flow.get("conditionExpression");
        if (cond == null) {
            cond = flow.get("conditionScript");
        }
        if (cond == null || !cond.isObject()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        JsonNode content = cond.get("content");
        if (content != null && content.isArray()) {
            for (JsonNode line : content) {
                if (sb.length() > 0) {
                    sb.append(' ');
                }
                sb.append(line.asText(""));
                if (sb.length() > 120) {
                    break;
                }
            }
        }
        String out = sb.toString().trim();
        if (out.isEmpty()) {
            return null;
        }
        return out.length() > 140 ? out.substring(0, 140) + "..." : out;
    }

    private static String text(JsonNode o, String field) {
        JsonNode n = o.get(field);
        if (n == null || !n.isTextual()) {
            return null;
        }
        String s = n.asText("").trim();
        return s.isEmpty() ? null : s;
    }

    private static String blankToNull(String s) {
        return s == null || s.isEmpty() ? null : s;
    }

    /** Resolve entity encoding (like ScriptScanner.unescape, standalone here). */
    static String unescape(String s) {
        Matcher m = ENTITY.matcher(s);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String rep = switch (m.group()) {
                case "&quot;" -> "\"";
                case "&apos;" -> "'";
                case "&lt;" -> "<";
                case "&gt;" -> ">";
                case "&amp;" -> "&";
                default -> {
                    // leave numeric entities outside the int/codepoint range as text
                    // (like ScriptScanner.unescape) — otherwise 500 on the diagram endpoint
                    try {
                        int cp = Integer.parseInt(m.group(1));
                        yield (cp >= 0 && Character.isValidCodePoint(cp))
                                ? new String(Character.toChars(cp)) : m.group();
                    } catch (RuntimeException e) {
                        yield m.group();
                    }
                }
            };
            m.appendReplacement(sb, Matcher.quoteReplacement(rep));
        }
        m.appendTail(sb);
        return sb.toString();
    }
}

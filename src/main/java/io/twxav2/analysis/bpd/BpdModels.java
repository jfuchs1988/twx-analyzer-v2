package io.twxav2.analysis.bpd;

import io.twxav2.ingest.TwxParseResult;
import io.twxav2.ingest.TwxParseResult.ParsedObject;
import org.w3c.dom.Attr;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Builds the graph model {@link Bpd} from prefix-25 objects: Teamworks flow
 * objects (implementation side) plus BPMN-2.0-XML from the {@code bpmn2Data}
 * element (namespace-agnostic via local names). Loads exactly once per parse
 * result (cache), since many structure rules read the same model.
 */
public final class BpdModels {

    /** BPMN elements that count as graph nodes. */
    private static final List<String> NODE_KINDS = List.of(
            "startEvent", "endEvent", "intermediateCatchEvent", "intermediateThrowEvent",
            "boundaryEvent", "exclusiveGateway", "parallelGateway", "inclusiveGateway",
            "eventBasedGateway", "complexGateway", "scriptTask", "userTask", "serviceTask",
            "sendTask", "receiveTask", "manualTask", "businessRuleTask", "callActivity",
            "subProcess", "transaction", "adHocSubProcess");

    // Identity semantics: TwxParseResult is a large record (expensive hashCode),
    // so an explicit identity key is used instead of record-equals.
    private static final Map<Identity, List<Bpd>> CACHE = new WeakHashMap<>();

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

    private BpdModels() { }

    /** All BPD graphs of the snapshot; loads and caches on first call. */
    public static synchronized List<Bpd> of(TwxParseResult parsed) {
        List<Bpd> cached = CACHE.get(new Identity(parsed));
        if (cached == null) {
            cached = new ArrayList<>();
            for (ParsedObject o : parsed.objects()) {
                if ("25".equals(o.prefix()) && o.content() != null) {
                    Bpd b = parse(o);
                    if (b != null) {
                        cached.add(b);
                    }
                }
            }
            cached = List.copyOf(cached);
            CACHE.put(new Identity(parsed), cached);
        }
        return cached;
    }

    // ---------- Parsing ----------

    private static Bpd parse(ParsedObject o) {
        try {
            Document tw = dom(o.content());
            Element root = tw.getDocumentElement();
            Element bpdEl = child(root, "bpd");
            if (bpdEl == null) {
                return null;
            }
            Map<String, Bpd.FlowObj> flowObjects = new HashMap<>();
            collectFlowObjects(bpdEl, flowObjects);
            boolean autoTracking = "true".equalsIgnoreCase(
                    firstDeepText(bpdEl, "autoTrackingEnabled", 0));
            int searchable = countDeep(bpdEl, "searchableField");

            List<Bpd.Node> nodes = new ArrayList<>();
            List<Bpd.Flow> flows = new ArrayList<>();
            Map<String, Bpd.Lane> lanes = new HashMap<>();
            Map<String, String> nodeLane = new HashMap<>();
            Map<String, String> nodeDefault = new HashMap<>();

            String bpmn = text(bpdEl, "bpmn2Data");
            if (bpmn != null && !bpmn.isBlank()) {
                Document doc = dom(bpmn);
                Element process = deepChild(doc.getDocumentElement(), "process", 0);
                Element scope = process != null ? process : doc.getDocumentElement();
                List<Element> flowEls = new ArrayList<>();
                for (Element e : children(scope)) {
                    String kind = local(e);
                    if ("sequenceFlow".equals(kind)) {
                        flowEls.add(e);
                    } else if ("lane".equals(kind)) {
                        readLane(e, lanes, nodeLane);
                    } else if (NODE_KINDS.contains(kind)) {
                        readNode(e, kind, nodes, nodeDefault);
                    }
                }
                // Lanes outside process (BPMN: laneSet directly in definitions)
                if (lanes.isEmpty()) {
                    for (Element e : children(doc.getDocumentElement())) {
                        if ("lane".equals(local(e))) {
                            readLane(e, lanes, nodeLane);
                        }
                    }
                }
                for (Element e : flowEls) {
                    flows.add(readFlow(e));
                }
                for (int i = 0; i < flows.size(); i++) {
                    Bpd.Flow f = flows.get(i);
                    String defId = nodeDefault.get(f.source());
                    if (defId != null && defId.equals(f.id())) {
                        flows.set(i, new Bpd.Flow(f.id(), f.source(), f.target(), f.name(),
                                f.condition(), true));
                    }
                }
            }
            // Merge lane assignment and FlowObject visibility into the nodes
            List<Bpd.Node> merged = new ArrayList<>();
            for (Bpd.Node n : nodes) {
                Bpd.FlowObj fo = flowObjects.get(n.id());
                boolean hidden = n.hidden() || (fo != null && fo.hidden());
                merged.add(new Bpd.Node(n.id(), n.kind(), n.name(), nodeLane.get(n.id()),
                        n.attachedToRef(), n.defaultFlowId(), n.defs(), n.activityType(),
                        n.humanTask(), hidden));
            }
            return new Bpd(o.id(), attr(bpdEl, "name"), merged, flows, lanes, flowObjects,
                    autoTracking, searchable).finish();
        } catch (Exception e) {
            return null; // unreadable BPD-XML: structure rules stay silent for this object
        }
    }

    private static void readNode(Element e, String kind, List<Bpd.Node> out,
                                 Map<String, String> nodeDefault) {
        String id = attr(e, "id");
        String attached = attr(e, "attachedToRef");
        String defId = attr(e, "default");
        String human = attrLocal(e, "isHumanTask");
        List<Bpd.Def> defs = new ArrayList<>();
        String[] type = { "true".equalsIgnoreCase(human) ? "UserTask" : null };
        boolean[] userTaskSettings = { false };
        scanNode(e, 0, defs, type, userTaskSettings);
        if (defId == null) {
            Element ext = child(e, "extensionElements");
            if (ext != null) {
                defId = text(ext, "default");
            }
        }
        if (defId != null && defId.isBlank()) {
            defId = null; // empty default element (TWX standard) = no default
        }
        if (defId != null) {
            nodeDefault.put(id, defId.trim());
        }
        out.add(new Bpd.Node(id, kind, attr(e, "name"), null, attached, defId, List.copyOf(defs),
                type[0], userTaskSettings[0], false));
    }

    /** Collects event definitions, activityType and userTaskSettings in the node subtree. */
    private static void scanNode(Element cur, int depth, List<Bpd.Def> defs,
                                 String[] activityType, boolean[] userTaskSettings) {
        if (depth > 6) {
            return;
        }
        for (Element c : children(cur)) {
            String cn = local(c);
            if (cn.endsWith("EventDefinition")) {
                defs.add(new Bpd.Def(cn, attrLocal(c, "eventImplId"), textOf(c)));
            } else if (cn.equals("activityType")) {
                String t = textOf(c);
                if (t != null && !t.isBlank()) {
                    activityType[0] = t;
                }
            } else if (cn.equals("userTaskSettings")) {
                userTaskSettings[0] = true;
            }
            scanNode(c, depth + 1, defs, activityType, userTaskSettings);
        }
    }

    private static Bpd.Flow readFlow(Element e) {
        String cond = null;
        for (Element c : children(e)) {
            String cn = local(c);
            if (cn.equals("conditionExpression") || cn.equals("conditionScript")) {
                String t = textOf(c);
                if (t != null && !t.isBlank()) {
                    cond = t.trim();
                    if (cn.equals("conditionExpression")) {
                        break;
                    }
                }
            }
        }
        return new Bpd.Flow(attr(e, "id"), attr(e, "sourceRef"), attr(e, "targetRef"),
                attr(e, "name"), cond, false);
    }

    private static void readLane(Element e, Map<String, Bpd.Lane> lanes,
                                 Map<String, String> nodeLane) {
        String id = attr(e, "id");
        boolean sys = "true".equalsIgnoreCase(attrLocal(e, "isSystemLane"));
        String participant = attrLocal(e, "partitionElementRef");
        List<String> refs = new ArrayList<>();
        for (Element c : children(e)) {
            String cn = local(c);
            if (cn.equals("flowNodeRef")) {
                String t = textOf(c);
                if (t != null && !t.isBlank()) {
                    refs.add(t.trim());
                    nodeLane.put(t.trim(), id);
                }
            } else if (cn.equals("partitionElementRef") && participant == null) {
                participant = textOf(c);
            }
        }
        lanes.put(id, new Bpd.Lane(id, attr(e, "name"), sys, participant, List.copyOf(refs)));
    }

    private static void collectFlowObjects(Element bpdEl, Map<String, Bpd.FlowObj> out) {
        for (Element e : children(bpdEl)) {
            if (!local(e).equals("flowObject")) {
                continue;
            }
            String id = attr(e, "id");
            int loopType = parseInt(text(e, "loopType"));
            boolean hidden = "true".equalsIgnoreCase(text(e, "isHidden"));
            StringBuilder impl = new StringBuilder();
            String attachedActivity = null;
            Element implEl = child(e, "implementation");
            if (implEl == null) {
                Element comp = child(e, "component");
                if (comp != null) {
                    implEl = child(comp, "implementation");
                }
            }
            if (implEl != null) {
                for (Element c : children(implEl)) {
                    if (impl.length() > 0) {
                        impl.append(',');
                    }
                    impl.append(local(c));
                    if (local(c).equals("attachedActivityId")) {
                        attachedActivity = textOf(c);
                    }
                }
            }
            out.put(id, new Bpd.FlowObj(id, attr(e, "componentType"), text(e, "name"),
                    loopType, hidden, impl.toString(), attachedActivity));
        }
    }

    // ---------- DOM helpers (namespace-agnostic via local names) ----------

    private static Document dom(String xml) throws Exception {
        var f = DocumentBuilderFactory.newDefaultInstance();
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        f.setNamespaceAware(false); // prefixes are handled by cutting getTagName
        return f.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
    }

    private static String local(Element e) {
        String t = e.getTagName();
        int i = t.indexOf(':');
        return i >= 0 ? t.substring(i + 1) : t;
    }

    /** Attribute independent of namespace prefix (exact or prefixed name). */
    private static String attrLocal(Element e, String name) {
        String v = e.getAttribute(name);
        if (!v.isEmpty()) {
            return v;
        }
        var attrs = e.getAttributes();
        for (int i = 0; i < attrs.getLength(); i++) {
            Attr a = (Attr) attrs.item(i);
            String n = a.getName();
            int c = n.indexOf(':');
            if ((c >= 0 ? n.substring(c + 1) : n).equals(name)) {
                return a.getValue();
            }
        }
        return null;
    }

    private static String attr(Element e, String name) {
        String v = e.getAttribute(name);
        return v.isEmpty() ? null : v;
    }

    private static List<Element> children(Element e) {
        List<Element> out = new ArrayList<>();
        NodeList nl = e.getChildNodes();
        for (int i = 0; i < nl.getLength(); i++) {
            if (nl.item(i) instanceof Element el) {
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

    /** Depth search for the first element with a local name (depth-limited). */
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
        return hit == null ? null : textOf(hit);
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
        return c == null ? null : textOf(c);
    }

    private static String textOf(Element e) {
        String t = e.getTextContent();
        return t == null ? null : t.trim();
    }

    private static int parseInt(String s) {
        try {
            return s == null ? 0 : Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}

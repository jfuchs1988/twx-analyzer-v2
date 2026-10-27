package io.twxav2.analysis.bpd;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Merged graph model of a BPD: Teamworks flow objects (implementation) plus
 * BPMN-2.0-XML from the {@code bpmn2Data} element. Indexes are set once at the
 * end of parsing ({@link #finish()}).
 */
public final class Bpd {

    /** BPMN node; kind = local BPMN name (startEvent, callActivity, ...). */
    public record Node(String id, String kind, String name, String laneId, String attachedToRef,
                       String defaultFlowId, List<Def> defs, String activityType,
                       boolean humanTask, boolean hidden) {

        /** Activity = callActivity with activityType (TWX exports EVERYTHING as callActivity). */
        public boolean isActivity() {
            return switch (kind) {
                case "callActivity", "scriptTask", "userTask", "serviceTask", "manualTask",
                     "businessRuleTask", "sendTask", "receiveTask" -> true;
                default -> false;
            };
        }

        /** Real human task: activityType UserTask or userTaskSettings present. */
        public boolean isHumanTask() {
            return humanTask || "UserTask".equals(activityType);
        }

        /** Automatic system activity (service or called tool). */
        public boolean isSystemActivity() {
            if (!isActivity() || isHumanTask()) {
                return false;
            }
            return switch (kind) {
                case "scriptTask", "serviceTask", "sendTask", "receiveTask",
                     "businessRuleTask", "manualTask" -> true;
                case "callActivity" -> "ServiceTask".equals(activityType)
                        || "CalledProcess".equals(activityType);
                default -> false;
            };
        }

        public boolean isGateway() {
            return kind.endsWith("Gateway");
        }

        public boolean isEvent() {
            return switch (kind) {
                case "startEvent", "endEvent", "catchEvent", "throwEvent", "boundaryEvent",
                     "intermediateCatchEvent", "intermediateThrowEvent" -> true;
                default -> false;
            };
        }

        public boolean hasDef(String type) {
            return defs.stream().anyMatch(d -> d.type().equals(type));
        }

        /** Error event (boundary or end event with errorEventDefinition). */
        public boolean hasErrorDef() {
            return hasDef("errorEventDefinition");
        }
    }

    /** Event definition (error/timer/ucaMessage) with optional event-impl or UCA reference. */
    public record Def(String type, String eventImplId, String ref) { }

    /** Sequence flow incl. resolved default marking of the source node. */
    public record Flow(String id, String source, String target, String name,
                       String condition, boolean isDefault) {

        public boolean conditional() {
            return condition != null && !condition.isBlank();
        }
    }

    /** Lane including its assigned nodes. */
    public record Lane(String id, String name, boolean systemLane, String participantRef,
                       List<String> flowNodeRefs) { }

    /** Teamworks flow object (implementation side of the activity). */
    public record FlowObj(String id, String componentType, String name, int loopType,
                          boolean hidden, String implementation, String attachedActivityId) {

        /** Repeated execution (loopType 0 = single). */
        public boolean multiInstance() {
            return loopType != 0;
        }

        /** No recognizable implementation (neither service/subactivity nor script). */
        public boolean emptyImplementation() {
            return implementation == null || implementation.isEmpty();
        }
    }

    private final String id;
    private final String name;
    private final List<Node> nodes;
    private final List<Flow> flows;
    private final Map<String, Lane> lanes;
    private final Map<String, FlowObj> flowObjects;
    private final boolean autoTracking;
    private final int searchableCount;

    private final Map<String, List<Flow>> outIdx = new HashMap<>();
    private final Map<String, List<Flow>> inIdx = new HashMap<>();
    private final Map<String, Node> byId = new HashMap<>();

    public Bpd(String id, String name, List<Node> nodes, List<Flow> flows, Map<String, Lane> lanes,
               Map<String, FlowObj> flowObjects, boolean autoTracking, int searchableCount) {
        this.id = id;
        this.name = name;
        this.nodes = List.copyOf(nodes);
        this.flows = List.copyOf(flows);
        this.lanes = Map.copyOf(lanes);
        this.flowObjects = Map.copyOf(flowObjects);
        this.autoTracking = autoTracking;
        this.searchableCount = searchableCount;
    }

    /** Build indexes; called once by the parser after populating. */
    public Bpd finish() {
        for (Node n : nodes) {
            byId.put(n.id(), n);
        }
        for (Flow f : flows) {
            outIdx.computeIfAbsent(f.source(), k -> new ArrayList<>()).add(f);
            inIdx.computeIfAbsent(f.target(), k -> new ArrayList<>()).add(f);
        }
        return this;
    }

    public String id() {
        return id;
    }

    public String name() {
        return name;
    }

    public List<Node> nodes() {
        return nodes;
    }

    public List<Flow> flows() {
        return flows;
    }

    public Map<String, Lane> lanes() {
        return lanes;
    }

    public Map<String, FlowObj> flowObjects() {
        return flowObjects;
    }

    public boolean autoTracking() {
        return autoTracking;
    }

    public int searchableCount() {
        return searchableCount;
    }

    public Node node(String nodeId) {
        return byId.get(nodeId);
    }

    public List<Flow> outgoing(String nodeId) {
        return outIdx.getOrDefault(nodeId, List.of());
    }

    public List<Flow> incoming(String nodeId) {
        return inIdx.getOrDefault(nodeId, List.of());
    }

    /** Lane of a node (via flowNodeRef), null if without a lane. */
    public Lane laneOf(Node n) {
        return n.laneId() == null ? null : lanes.get(n.laneId());
    }

    public boolean inSystemLane(Node n) {
        Lane l = laneOf(n);
        return l != null && l.systemLane();
    }

    /** Active successors: sequence flows plus boundary attachment (error path). */
    public List<String> successors(String nodeId) {
        List<String> out = new ArrayList<>();
        for (Flow f : outgoing(nodeId)) {
            out.add(f.target());
        }
        for (Node n : nodes) {
            if (nodeId.equals(n.attachedToRef()) && !out.contains(n.id())) {
                out.add(n.id());
            }
        }
        return out;
    }

    /** Reachability via active edges (forward, without the start node itself). */
    public Set<String> reachableFrom(String startId) {
        Set<String> seen = new HashSet<>();
        ArrayDeque<String> todo = new ArrayDeque<>();
        todo.add(startId);
        while (!todo.isEmpty()) {
            String cur = todo.poll();
            if (!seen.add(cur)) {
                continue;
            }
            todo.addAll(successors(cur));
        }
        seen.remove(startId);
        return seen;
    }

    /** true if {@code to} is reachable from {@code from} via active edges. */
    public boolean reaches(String from, String to) {
        return reachableFrom(from).contains(to);
    }

    /**
     * Finds a cycle consisting exclusively of unconditional flows (no default,
     * no condition, no self-edge): such a path never leaves the loop.
     * Returns the node IDs of the cycle or null.
     */
    public List<String> unconditionalCycle() {
        Map<String, Integer> color = new HashMap<>(); // 0=white 1=gray 2=black
        Map<String, String> parent = new HashMap<>();
        for (Node n : nodes) {
            if (color.getOrDefault(n.id(), 0) != 0) {
                continue;
            }
            List<String> cyc = dfsCycle(n.id(), color, parent);
            if (cyc != null) {
                return cyc;
            }
        }
        return null;
    }

    private List<String> dfsCycle(String start, Map<String, Integer> color, Map<String, String> parent) {
        ArrayDeque<String> stack = new ArrayDeque<>();
        stack.push(start);
        while (!stack.isEmpty()) {
            String cur = stack.peek();
            color.put(cur, 1);
            String next = null;
            for (Flow f : outgoing(cur)) {
                if (f.isDefault() || f.conditional() || f.source().equals(f.target())) {
                    continue; // only unconditional, real edges
                }
                int tc = color.getOrDefault(f.target(), 0);
                if (tc == 1) {
                    List<String> cyc = new ArrayList<>();
                    cyc.add(f.target());
                    String w = cur;
                    while (w != null && !w.equals(f.target())) {
                        cyc.add(w);
                        w = parent.get(w);
                    }
                    return cyc;
                }
                if (tc == 0) {
                    next = f.target();
                    break;
                }
            }
            if (next == null) {
                stack.pop();
                color.put(cur, 2);
            } else {
                parent.put(next, cur);
                stack.push(next);
            }
        }
        return null;
    }

    /**
     * Longest chain of consecutive activities without gateway/event branching
     * — approximation for "long sequential chain". Starts at the start events.
     */
    public int longestActivityChain() {
        int best = 0;
        for (Node n : nodes) {
            if (n.kind().equals("startEvent")) {
                best = Math.max(best, chainDfs(n.id(), new HashSet<>()));
            }
        }
        return best;
    }

    private int chainDfs(String nodeId, Set<String> guard) {
        Node n = node(nodeId);
        if (n == null || !guard.add(nodeId)) {
            return 0;
        }
        int best = 0;
        List<Flow> outs = outgoing(nodeId);
        boolean branching = n.isGateway() || n.isEvent() || outs.size() != 1;
        for (Flow f : outs) {
            if (!f.conditional() && !f.isDefault()) {
                best = Math.max(best, chainDfs(f.target(), guard));
            }
        }
        guard.remove(nodeId);
        int self = (!branching && n.isActivity()) ? 1 : 0;
        return self + best;
    }

    /** Active nodes reachable from start events (without boundary events). */
    public Set<String> liveNodes() {
        Set<String> live = new HashSet<>();
        for (Node n : nodes) {
            if (n.kind().equals("startEvent")) {
                live.add(n.id());
                live.addAll(reachableFrom(n.id()));
            }
        }
        return live;
    }
}

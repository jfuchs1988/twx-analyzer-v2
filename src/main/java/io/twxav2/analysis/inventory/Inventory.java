package io.twxav2.analysis.inventory;

import io.twxav2.ingest.TwxParseResult;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Inventory model (B2d): object catalog, cross-references and toolkit bundles from
 * the parse result. A reference = an occurrence of the full object id ("praefix.uuid")
 * in the content of another object — raw-coded OR entity-encoded in the
 * TWX (in FlowObjects/bpmn2Data), so both are scanned. Loads exactly once
 * per parse result (cache like BpdModels).
 */
public final class Inventory {

    /** Cross-reference: source object, found-in word (element name or "text"), target ("1.<uuid>"). */
    public record Ref(String sourceId, String element, String target) { }

    /** p.uuid tokens (carriers: tag text, attributes such as REST binding names, JSON, plain text). */
    private static final Pattern TOKEN = Pattern.compile("\\b(\\d+\\.[0-9a-fA-F-]{36})\\b");
    /** Real cross references: "/praefix.uuid" as element text (TWX serialization). */
    private static final Pattern REF_ELEMENT = Pattern.compile("<([\\w:]+)\\s*>/(\\d+\\.[0-9a-fA-F-]{36})</");

    private static final Map<Identity, Inventory> CACHE = new WeakHashMap<>();

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

    public static synchronized Inventory of(TwxParseResult parsed) {
        return CACHE.computeIfAbsent(new Identity(parsed), id -> new Inventory(parsed));
    }

    private final TwxParseResult parsed;
    private final Map<String, TwxParseResult.ParsedObject> byId = new HashMap<>();
    private final Map<String, Integer> countByPrefix = new HashMap<>();
    /** target id -> source objects (all id occurrences except in the target itself). */
    private final Map<String, Set<String>> referrers = new HashMap<>();
    private final List<Ref> elementRefs = new ArrayList<>();
    private final Map<String, Set<String>> serviceCalls = new HashMap<>();
    private final Map<String, List<TwxParseResult.ParsedToolkit>> toolkitsByAcronym =
            new LinkedHashMap<>();

    private Inventory(TwxParseResult parsed) {
        this.parsed = parsed;
        for (var o : parsed.objects()) {
            byId.put(o.id(), o);
            countByPrefix.merge(o.prefix(), 1, Integer::sum);
        }
        scan();
        for (var t : parsed.toolkits()) {
            toolkitsByAcronym.computeIfAbsent(
                    t.projectShortName() == null ? t.toolkitId() : t.projectShortName(),
                    k -> new ArrayList<>()).add(t);
        }
    }

    private void scan() {
        Set<Ref> seen = new LinkedHashSet<>();
        for (var o : parsed.objects()) {
            String content = o.content();
            if (content == null) {
                continue;
            }
            collectTokens(o.id(), content, seen);
            if (content.indexOf('&') >= 0) {
                collectTokens(o.id(), unesc(content), seen); // entity-encoded FlowObjects
            }
        }
        elementRefs.addAll(seen);
        for (Ref r : elementRefs) {
            if (!r.sourceId().equals(r.target())) {
                referrers.computeIfAbsent(r.target(), k -> new HashSet<>())
                        .add(r.sourceId());
            }
            if (r.sourceId().startsWith("1.") && r.target().startsWith("1.")
                    && r.element().endsWith("attachedProcessRef")) {
                serviceCalls.computeIfAbsent(r.sourceId(), k -> new HashSet<>())
                        .add(r.target());
            }
        }
    }

    private void collectTokens(String sourceId, String content, Set<Ref> sink) {
        Matcher t = TOKEN.matcher(content);
        while (t.find()) {
            sink.add(new Ref(sourceId, "text", t.group(1)));
        }
        Matcher m = REF_ELEMENT.matcher(content);
        while (m.find()) { // element name as finding context for precise messages
            sink.add(new Ref(sourceId, m.group(1), m.group(2)));
        }
    }

    /** Entity decoding for scan purposes (TWX uses the five named entities). */
    static String unesc(String s) {
        if (s.indexOf('&') < 0) {
            return s;
        }
        return s.replace("&gt;", ">").replace("&lt;", "<")
                .replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&");
    }

    // ---------- Access ----------

    public TwxParseResult parsed() {
        return parsed;
    }

    public List<TwxParseResult.ParsedObject> objects() {
        return parsed.objects();
    }

    public TwxParseResult.ParsedObject object(String id) {
        return byId.get(id);
    }

    public int count(String prefix) {
        return countByPrefix.getOrDefault(prefix, 0);
    }

    /**
     * Objects without stored content (threshold TwxParser.MAX_CONTENT_BYTES):
     * their references are invisible to heuristics — hits can be
     * false positives (note text in the orphan rules).
     */
    public int objectsWithoutContent() {
        int n = 0;
        for (var o : parsed.objects()) {
            if (o.content() == null) {
                n++;
            }
        }
        return n;
    }

    public List<Ref> elementRefs() {
        return elementRefs;
    }

    /** Does ANOTHER object reference this id? (orphan check) */
    public boolean referencedElsewhere(String objectId) {
        Set<String> src = referrers.get(objectId);
        if (src == null) {
            return false;
        }
        return src.stream().anyMatch(s -> !s.equals(objectId));
    }

    /** Service call graph: calling serviceId -> called service IDs. */
    public Map<String, Set<String>> serviceCalls() {
        return serviceCalls;
    }

    /** Cyclic chains in the service graph as node lists (one per cycle). */
    public List<List<String>> serviceCycles() {
        Map<String, Integer> state = new HashMap<>();
        List<List<String>> cycles = new ArrayList<>();
        for (String root : serviceCalls.keySet()) {
            if (state.getOrDefault(root, 0) == 0) {
                dfs(root, new ArrayList<>(), state, cycles);
            }
        }
        return cycles;
    }

    private void dfs(String u, List<String> stack, Map<String, Integer> state,
                     List<List<String>> cycles) {
        state.put(u, 1);
        stack.add(u);
        for (String v : serviceCalls.getOrDefault(u, Set.of())) {
            int s = state.getOrDefault(v, 0);
            if (s == 0) {
                dfs(v, stack, state, cycles);
            } else if (s == 1) {
                cycles.add(List.copyOf(stack.subList(stack.indexOf(v), stack.size())));
            }
        }
        stack.remove(stack.size() - 1);
        state.put(u, 2);
    }

    /** Longest toolkit chain below the app (toolkit levels, 0 = none);
     *  cyclic roots do not count (report R-DEP-001), finite chains do. */
    public int toolkitChainDepth() {
        int max = 0;
        for (var d : parsed.manifest().dependencies()) {
            int c = chainDepth(d.projectShortName(), new HashSet<>());
            if (c != Integer.MAX_VALUE) {
                max = Math.max(max, c);
            }
        }
        return max;
    }

    /** Acronym names involved in toolkit cycles. */
    public List<String> toolkitCycles() {
        Set<String> roots = new HashSet<>();
        for (var t : parsed.toolkits()) {
            if (t.projectShortName() != null) {
                roots.add(t.projectShortName());
            }
        }
        for (var d : parsed.manifest().dependencies()) {
            if (d.projectShortName() != null) {
                roots.add(d.projectShortName());
            }
        }
        List<String> found = new ArrayList<>();
        for (String r : roots) {
            if (chainDepth(r, new HashSet<>()) == Integer.MAX_VALUE) {
                found.add(r);
            }
        }
        return found;
    }

    /** Toolkit levels below shortName (1 = chain ends at the toolkit); MAX on cycle. */
    private int chainDepth(String shortName, Set<String> path) {
        if (shortName == null) {
            return 1;
        }
        if (!path.add(shortName)) {
            return Integer.MAX_VALUE;
        }
        int max = 1;
        for (var tk : toolkitsByAcronym.getOrDefault(shortName, List.of())) {
            for (var child : tk.nested()) {
                int d = chainDepth(child.projectShortName(), path);
                if (d == Integer.MAX_VALUE) {
                    path.remove(shortName);
                    return Integer.MAX_VALUE;
                }
                max = Math.max(max, 1 + d);
            }
        }
        path.remove(shortName); // only the CURRENT path is a cycle, not a visited child
        return max;
    }

    /** Embedded toolkit ZIPs per acronym (multiple entries = multiple versions). */
    public Map<String, List<TwxParseResult.ParsedToolkit>> toolkitsByAcronym() {
        return toolkitsByAcronym;
    }
}

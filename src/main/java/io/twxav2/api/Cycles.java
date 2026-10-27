package io.twxav2.api;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Cycle detection in the toolkit dependency graph (B4.1, shown in the graph view).
 * A node lies in a cycle if it is reachable from itself via >=1 edge;
 * an edge belongs to a cycle if target -> source leads back.
 * Graphs are small (dozens of nodes), so the naive reachability search
 * still runs in milliseconds.
 */
final class Cycles {

    private Cycles() { }

    /** Nodes that lie on a cycle, from an edge list (adjacent: from -> to). */
    static Set<String> cyclicNodes(List<String[]> edges) {
        Map<String, List<String>> adj = new HashMap<>();
        Set<String> nodes = new HashSet<>();
        for (String[] e : edges) {
            if (e[0] == null || e[1] == null) {
                continue;
            }
            adj.computeIfAbsent(e[0], k -> new ArrayList<>()).add(e[1]);
            nodes.add(e[0]);
            nodes.add(e[1]);
        }
        Set<String> cyclic = new HashSet<>();
        for (String n : nodes) {
            if (reaches(adj, n, n)) {
                cyclic.add(n);
            }
        }
        return cyclic;
    }

    /** true if `to` is reachable from `from` via >=1 edge. */
    static boolean reaches(Map<String, List<String>> adj, String from, String to) {
        Set<String> seen = new HashSet<>();
        List<String> frontier = new ArrayList<>();
        for (String next : adj.getOrDefault(from, List.of())) {
            if (next.equals(to)) {
                return true;
            }
            if (seen.add(next)) {
                frontier.add(next);
            }
        }
        while (!frontier.isEmpty()) {
            List<String> nextLevel = new ArrayList<>();
            for (String n : frontier) {
                for (String next : adj.getOrDefault(n, List.of())) {
                    if (next.equals(to)) {
                        return true;
                    }
                    if (seen.add(next)) {
                        nextLevel.add(next);
                    }
                }
            }
            frontier = nextLevel;
        }
        return false;
    }

    /** Adjacency from an edge list (for reaches calls without rebuilding). */
    static Map<String, List<String>> adjacency(List<String[]> edges) {
        Map<String, List<String>> adj = new HashMap<>();
        for (String[] e : edges) {
            if (e[0] != null && e[1] != null) {
                adj.computeIfAbsent(e[0], k -> new ArrayList<>()).add(e[1]);
            }
        }
        return adj;
    }
}

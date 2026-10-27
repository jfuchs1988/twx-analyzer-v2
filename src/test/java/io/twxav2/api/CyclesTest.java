package io.twxav2.api;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Cycle detection in the toolkit dependency graph (package-private -> test in the package). */
class CyclesTest {

    /** Edge as String[] (List.of would flatten a single array via varargs). */
    private static String[] edge(String from, String to) {
        return new String[] {from, to};
    }

    @Test
    void simpleCycleBothNodes() {
        Set<String> cyclic = Cycles.cyclicNodes(List.of(edge("a", "b"), edge("b", "a")));
        assertEquals(Set.of("a", "b"), cyclic);
    }

    @Test
    void selfEdgeIsCycle() {
        assertEquals(Set.of("x"), Cycles.cyclicNodes(List.<String[]>of(edge("x", "x"))));
    }

    @Test
    void acyclicChainNoCycle() {
        assertTrue(Cycles.cyclicNodes(List.of(
                edge("a", "b"), edge("b", "c"), edge("a", "c"))).isEmpty());
    }

    @Test
    void cycleInRightInMiddleOnlyParticipants() {
        // a -> b -> c -> b: only b and c lie on the cycle
        assertEquals(Set.of("b", "c"), Cycles.cyclicNodes(List.of(
                edge("a", "b"), edge("b", "c"), edge("c", "b"))));
    }

    @Test
    void edgesWithNullsAreIgnored() {
        assertTrue(Cycles.cyclicNodes(List.of(edge(null, "b"), edge("c", null))).isEmpty());
        assertTrue(Cycles.adjacency(List.<String[]>of(edge(null, "b"))).isEmpty());
    }

    @Test
    void emptyEdgeList() {
        assertTrue(Cycles.cyclicNodes(List.of()).isEmpty());
    }

    @Test
    void reichweite() {
        Map<String, List<String>> adj = Cycles.adjacency(List.of(edge("a", "b"), edge("b", "c")));
        assertTrue(Cycles.reaches(adj, "a", "b"));
        assertTrue(Cycles.reaches(adj, "a", "c"));   // across two edges
        assertFalse(Cycles.reaches(adj, "c", "a"));
        assertFalse(Cycles.reaches(adj, "a", "a"));  // no back edge, no cycle
        assertFalse(Cycles.reaches(adj, "x", "a"));  // unknown node
    }

    @Test
    void reichweiteInEndlosschleifeTerminiert() {
        Map<String, List<String>> adj = Cycles.adjacency(List.of(edge("a", "b"), edge("b", "a")));
        assertTrue(Cycles.reaches(adj, "a", "a"));   // a -> b -> a
        assertFalse(Cycles.reaches(adj, "a", "z"));  // seen set bounds the search
    }
}

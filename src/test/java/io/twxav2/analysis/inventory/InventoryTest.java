package io.twxav2.analysis.inventory;

import io.twxav2.analysis.ModelsFixtures;
import io.twxav2.ingest.TwxParseResult;
import io.twxav2.ingest.TwxParseResult.ParsedObject;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static io.twxav2.analysis.ModelsFixtures.dep;
import static io.twxav2.analysis.ModelsFixtures.obj;
import static io.twxav2.analysis.ModelsFixtures.result;
import static io.twxav2.analysis.ModelsFixtures.toolkit;
import static io.twxav2.analysis.ModelsFixtures.uuid;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Inventory model (B2d) with synthetic TwxParseResults: reference scan
 * (raw + entity-encoded), orphan check, service graph with cycles,
 * toolkit chains and cycles, cache.
 */
class InventoryTest {

    private static final String SVC_A = "1." + uuid('a');
    private static final String SVC_B = "1." + uuid('b');
    private static final String SVC_C = "1." + uuid('c');
    private static final String CV = "64." + uuid('d');

    // ---------- Cache and basic accessors ----------

    @Test
    void ofReturnsPerParseResultTheSameInstance() {
        var pr = result(List.of(obj(SVC_A, "A", "twProcess", "<x/>")));
        assertSame(Inventory.of(pr), Inventory.of(pr));
    }

    @Test
    void parsedAndObjectsReturnSameResult() {
        var o = obj(SVC_A, "A", "twProcess", "<x/>");
        var pr = result(List.of(o));
        assertSame(pr, Inventory.of(pr).parsed());
        assertEquals(List.of(o), Inventory.of(pr).objects());
    }

    @Test
    void objectAfterIdUnknownYieldsNull() {
        var pr = result(List.of(obj(SVC_A, "A", "twProcess", "<x/>")));
        assertSame(Inventory.of(pr).object(SVC_A).id(), SVC_A);
        assertNull(Inventory.of(pr).object("1.unbekannt"));
    }

    @Test
    void countPerPrefix() {
        var pr = result(List.of(
                obj(SVC_A, "A", "twProcess", "<x/>"),
                obj(SVC_B, "B", "twProcess", "<x/>"),
                obj(CV, "CV", "twCoachView", "<x/>")));
        var inv = Inventory.of(pr);
        assertEquals(2, inv.count("1"));
        assertEquals(1, inv.count("64"));
        assertEquals(0, inv.count("12"));
    }

    @Test
    void objectsWithoutContentEstimatesLargeNull() {
        var pr = result(List.of(
                obj(SVC_A, "A", "twProcess", null),
                obj(SVC_B, "B", "twProcess", "<x/>"),
                obj(CV, "CV", "twCoachView", null)));
        assertEquals(2, Inventory.of(pr).objectsWithoutContent());
    }

    // ---------- Reference scan ----------

    @Test
    void refElementFindsReferenceWithElementNames() {
        var pr = result(List.of(
                obj(SVC_A, "A", "twProcess",
                        "<flowObject><attachedProcessRef>/" + SVC_B + "</attachedProcessRef></flowObject>"),
                obj(SVC_B, "B", "twProcess", "<x/>")));
        var inv = Inventory.of(pr);
        assertTrue(inv.elementRefs().stream()
                .anyMatch(r -> r.sourceId().equals(SVC_A)
                        && r.element().equals("attachedProcessRef")
                        && r.target().equals(SVC_B)));
    }

    @Test
    void bareTokenInTextCountAsTextReference() {
        var pr = result(List.of(
                obj(SVC_A, "A", "twProcess", "kommentar: " + SVC_B + " ende"),
                obj(SVC_B, "B", "twProcess", "<x/>")));
        var inv = Inventory.of(pr);
        assertTrue(inv.elementRefs().stream()
                .anyMatch(r -> r.element().equals("text") && r.target().equals(SVC_B)));
    }

    @Test
    void entityEncodedReferenceIsUnpackedAndFound() {
        var pr = result(List.of(
                obj(SVC_A, "A", "twProcess",
                        "&lt;attachedProcessRef&gt;/" + SVC_B + "&lt;/attachedProcessRef&gt;"),
                obj(SVC_B, "B", "twProcess", "<x/>")));
        var inv = Inventory.of(pr);
        assertTrue(inv.elementRefs().stream()
                .anyMatch(r -> r.element().equals("attachedProcessRef")
                        && r.target().equals(SVC_B)));
    }

    @Test
    void contentWithoutAmpersandIsOnlyRawScanned() {
        var pr = result(List.of(obj(SVC_A, "A", "twProcess", "nur " + SVC_B + " text")));
        assertEquals(1, Inventory.of(pr).elementRefs().size());
    }

    @Test
    void unescTranslatedTheFiveNamedEntities() {
        assertEquals("<a href=\"x\">&amp;'y'</a>",
                Inventory.unesc("&lt;a href=&quot;x&quot;&gt;&amp;amp;&apos;y&apos;&lt;/a&gt;"));
    }

    @Test
    void unescLetsTextWithoutAmpersandUnchanged() {
        assertEquals("plain <x/>", Inventory.unesc("plain <x/>"));
    }

    @Test
    void unescAbortsNotAtHalfDayEntities() {
        assertEquals("&unknown;", Inventory.unesc("&unknown;"));
    }

    @Test
    void nullContentIsSkipped() {
        var pr = result(List.of(
                obj(SVC_A, "A", "twProcess", null),
                obj(SVC_B, "B", "twProcess", SVC_A)));
        // reference from B to A exists, but A carries no references.
        assertTrue(Inventory.of(pr).elementRefs().stream()
                .noneMatch(r -> r.sourceId().equals(SVC_A)));
    }

    // ---------- Orphan check ----------

    @Test
    void referencedElsewhereFindsForeignReference() {
        var pr = result(List.of(
                obj(SVC_A, "A", "twProcess", "verweist " + SVC_B),
                obj(SVC_B, "B", "twProcess", "<x/>")));
        assertTrue(Inventory.of(pr).referencedElsewhere(SVC_B));
        assertFalse(Inventory.of(pr).referencedElsewhere(SVC_A));
    }

    @Test
    void selfReferenceIsNoForeignReference() {
        var pr = result(List.of(obj(SVC_A, "A", "twProcess", "text " + SVC_A + " mehr")));
        assertFalse(Inventory.of(pr).referencedElsewhere(SVC_A));
    }

    @Test
    void fullyUnknownIdIsNotReferenced() {
        var pr = result(List.of(obj(SVC_A, "A", "twProcess", "<x/>")));
        assertFalse(Inventory.of(pr).referencedElsewhere("12." + uuid('f')));
    }

    // ---------- Service-Graph ----------

    @Test
    void serviceCallsOnlyFrom1To1WithAttachedProcessRef() {
        var pr = result(List.of(
                obj(SVC_A, "A", "twProcess",
                        "<attachedProcessRef>/" + SVC_B + "</attachedProcessRef>"),
                obj(SVC_B, "B", "twProcess", "<x/>"),
                obj(CV, "CV", "twCoachView",
                        "<attachedProcessRef>/" + SVC_B + "</attachedProcessRef>")));
        var calls = Inventory.of(pr).serviceCalls();
        assertEquals(Set.of(SVC_B), calls.get(SVC_A));
        assertFalse(calls.containsKey(CV)); // source object must have prefix 1
    }

    @Test
    void otherElementIsNoServiceCall() {
        var pr = result(List.of(
                obj(SVC_A, "A", "twProcess", "<someOther>/" + SVC_B + "</someOther>"),
                obj(SVC_B, "B", "twProcess", "<x/>")));
        assertTrue(Inventory.of(pr).serviceCalls().isEmpty());
    }

    @Test
    void serviceCyclesRecognizesPairChain() {
        var pr = result(List.of(
                obj(SVC_A, "A", "twProcess",
                        "<attachedProcessRef>/" + SVC_B + "</attachedProcessRef>"),
                obj(SVC_B, "B", "twProcess",
                        "<attachedProcessRef>/" + SVC_A + "</attachedProcessRef>")));
        var cycles = Inventory.of(pr).serviceCycles();
        assertEquals(1, cycles.size());
        assertEquals(Set.of(SVC_A, SVC_B), Set.copyOf(cycles.get(0)));
    }

    @Test
    void serviceCyclesRecognizesTripleChain() {
        var pr = result(List.of(
                obj(SVC_A, "A", "twProcess", "<attachedProcessRef>/" + SVC_B + "</attachedProcessRef>"),
                obj(SVC_B, "B", "twProcess", "<attachedProcessRef>/" + SVC_C + "</attachedProcessRef>"),
                obj(SVC_C, "C", "twProcess", "<attachedProcessRef>/" + SVC_A + "</attachedProcessRef>")));
        var cycles = Inventory.of(pr).serviceCycles();
        assertEquals(1, cycles.size());
        assertEquals(3, cycles.get(0).size());
    }

    @Test
    void noCycleAtDirectedTree() {
        var pr = result(List.of(
                obj(SVC_A, "A", "twProcess", "<attachedProcessRef>/" + SVC_B + "</attachedProcessRef>"),
                obj(SVC_B, "B", "twProcess", "<attachedProcessRef>/" + SVC_C + "</attachedProcessRef>"),
                obj(SVC_C, "C", "twProcess", "<x/>")));
        assertTrue(Inventory.of(pr).serviceCycles().isEmpty());
    }

    @Test
    void triangleWithoutBackEdgeIsCycleFree() {
        var pr = result(List.of(
                obj(SVC_A, "A", "twProcess",
                        "<attachedProcessRef>/" + SVC_B + "</attachedProcessRef>"
                                + "<attachedProcessRef>/" + SVC_C + "</attachedProcessRef>"),
                obj(SVC_B, "B", "twProcess", "<attachedProcessRef>/" + SVC_C + "</attachedProcessRef>"),
                obj(SVC_C, "C", "twProcess", "<x/>")));
        assertTrue(Inventory.of(pr).serviceCycles().isEmpty());
    }

    // ---------- Toolkit chains ----------

    @Test
    void chainsDepthWithoutDependenciesNull() {
        var pr = result(List.of(), List.of(), List.of());
        assertEquals(0, Inventory.of(pr).toolkitChainDepth());
        assertTrue(Inventory.of(pr).toolkitCycles().isEmpty());
    }

    @Test
    void chainsDepthEmbeddedToolkitIsTwo() {
        var pr = result(List.of(),
                List.of(toolkit("TK1", List.of(dep("TK2"))), toolkit("TK2", List.of())),
                List.of(dep("TK1")));
        assertEquals(2, Inventory.of(pr).toolkitChainDepth());
    }

    @Test
    void depthChainFourLevels() {
        var pr = result(List.of(),
                List.of(toolkit("TK1", List.of(dep("TK2"))),
                        toolkit("TK2", List.of(dep("TK3"))),
                        toolkit("TK3", List.of())),
                List.of(dep("TK1")));
        assertEquals(3, Inventory.of(pr).toolkitChainDepth());
    }

    @Test
    void dependencyWithoutToolkitFileCountAsOne() {
        var pr = result(List.of(), List.of(), List.of(dep("TK9")));
        assertEquals(1, Inventory.of(pr).toolkitChainDepth());
    }

    @Test
    void toolkitCycleIsReportedAndChainIgnored() {
        var pr = result(List.of(),
                List.of(toolkit("TK1", List.of(dep("TK2"))), toolkit("TK2", List.of(dep("TK1")))),
                List.of(dep("TK1")));
        var inv = Inventory.of(pr);
        assertEquals(List.of("TK1", "TK2"), inv.toolkitCycles().stream().sorted().toList());
        assertEquals(0, inv.toolkitChainDepth()); // MAX-marked branches do not count
    }

    @Test
    void sharedToolkitNoCycle() {
        // TK1 and TK2 both depend on TK3 — no cycle, only a merged path.
        var pr = result(List.of(),
                List.of(toolkit("TK1", List.of(dep("TK3"))),
                        toolkit("TK2", List.of(dep("TK3"))),
                        toolkit("TK3", List.of())),
                List.of(dep("TK1"), dep("TK2")));
        var inv = Inventory.of(pr);
        assertTrue(inv.toolkitCycles().isEmpty());
        assertEquals(2, inv.toolkitChainDepth());
    }

    @Test
    void toolkitsByAcronymGroupsVersions() {
        var pr = result(List.of(),
                List.of(toolkit("TK1", List.of()), toolkit("TK1", List.of()), toolkit("TK2", List.of())),
                List.of());
        var groups = Inventory.of(pr).toolkitsByAcronym();
        assertEquals(2, groups.size());
        assertEquals(2, groups.get("TK1").size());
        assertEquals(1, groups.get("TK2").size());
    }

    @Test
    void toolkitWithoutAbbreviationIsUnderToolkitIdLed() {
        var unnamed = new TwxParseResult.ParsedToolkit("tk-xyz", "Projekt", null,
                "1.0", "sha", 1, 0, List.of(), List.of(), java.util.Map.of());
        var pr = result(List.of(), List.of(unnamed), List.of());
        assertTrue(Inventory.of(pr).toolkitsByAcronym().containsKey("tk-xyz"));
    }
}

package io.twxav2.analysis.ref;

import io.twxav2.analysis.ref.model.TwxModel;
import io.twxav2.ingest.TwxParseResult;
import io.twxav2.ingest.TwxParseResult.Dependency;
import io.twxav2.ingest.TwxParseResult.Manifest;
import io.twxav2.ingest.TwxParseResult.ParsedObject;
import io.twxav2.ingest.TwxParseResult.ParsedToolkit;
import io.twxav2.ingest.TwxParseResult.ParseError;
import io.twxav2.ingest.TwxParseResult.ToolkitObject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Adapter TwxParseResult -> reference model (incl. documented deviations). */
class RefModelTest {

    private static final String GUID = "11111111-2222-3333-4444-555555555555";

    private static TwxParseResult parsed() {
        return new TwxParseResult(
                new Manifest("Demo App", "DEMO", "Beschreibung", "Main", "1.0.0",
                        "snap", "2026-01-01", "8.6.9", "FP25000", false,
                        List.of(new Dependency("p-1", "Sys Kit", "SYSKIT", "snap-sys", "1.0", 0),
                                new Dependency("p-2", "Biz Kit", "BIZKIT", "snap-biz", "2.0", 1))),
                List.of(
                        new ParsedObject("61.svc", "61", "service", "Svc A", "d", "sha1",
                                "<service operationType=\"service\"/>"),
                        new ParsedObject("64." + GUID, "64", "bpd", GUID, null, "sha2",
                                "<process name=\"" + GUID + "\"/>"),
                        new ParsedObject("64.host", "64", "bpd", "Host", null, "sha3",
                                "<process><flowObject id=\"f1\"><name>Do Thing</name>"
                                        + "<embeddedProcessId>64." + GUID + "</embeddedProcessId>"
                                        + "</flowObject></process>"),
                        new ParsedObject("25.big", "25", "managedAsset", "Big", null, "sha4", null)),
                List.of(new ParsedToolkit("snap-biz", "Biz Kit", "BIZKIT", "2.0", "shaT",
                        4096, 12, List.of(new Dependency("p-3", "Deep", "DEEP", "snap-deep", "3", 0)),
                        List.of(new ToolkitObject("1.tk-guid", "Kit Service", "process", "")),
                        java.util.Map.of())),
                List.of(new ParseError("objects/x.xml", "not well-formed")));
    }

    @Test
    void applicationAndObjects() {
        TwxModel m = RefModel.from(parsed());
        assertEquals("Demo App", m.app.name);
        assertEquals("DEMO", m.app.acronym);
        assertEquals("1.0.0", m.app.snapshotName);
        assertEquals("8.6.9", m.app.buildVersion);
        assertFalse(m.app.toolkit);
        assertEquals(4, m.app.objects.size());
        var svc = m.app.objects.get("61.svc");
        assertEquals("Svc A", svc.name);
        assertEquals("service", svc.type);
        assertEquals("<service operationType=\"service\"/>", svc.xml);
        // large object without content -> empty XML (as with a missing file)
        assertEquals("", m.app.objects.get("25.big").xml);
        assertSame(m.app, svc.pkg);
    }

    @Test
    void nullIsEmpty() {
        var single = new TwxParseResult(
                new Manifest(null, null, null, null, null, null, null, null, null, null, List.of()),
                List.of(new ParsedObject("1.a", "1", null, null, null, "s", null)),
                List.of(), List.of());
        TwxModel m = RefModel.from(single);
        assertEquals("", m.app.name);
        assertEquals("", m.app.acronym);
        assertFalse(m.app.toolkit);
        var o = m.app.objects.get("1.a");
        assertEquals("", o.name);
        assertEquals("", o.type);
        assertEquals("", o.xml);
    }

    @Test
    void dependenciesWithSystemDerivation() {
        TwxModel m = RefModel.from(parsed());
        assertEquals(2, m.app.dependencies.size());
        assertTrue(m.app.dependencies.get(0).system, "SYKURZSYS-Kuerzel -> system");
        assertFalse(m.app.dependencies.get(1).system);
        assertEquals("snap-biz", m.app.dependencies.get(1).snapshotId);
        assertEquals("Biz Kit", m.app.dependencies.get(1).name);
    }

    @Test
    void toolkitObjectsAsStubs() {
        TwxModel m = RefModel.from(parsed());
        var tk = m.toolkits.get("snap-biz");
        assertNotNull(tk);
        assertTrue(tk.toolkit);
        assertEquals("Biz Kit", tk.name);
        assertEquals("2.0", tk.snapshotName);
        assertEquals(4096, tk.zipSize);
        assertEquals(1, tk.dependencies.size());
        assertEquals("snap-deep", tk.dependencies.get(0).snapshotId);
        // stubs (id/name/type, no XML): references to toolkit objects resolve,
        // toolkit-internal checks see empty content.
        var stub = tk.objects.get("1.tk-guid");
        assertNotNull(stub);
        assertEquals("Kit Service", stub.name);
        assertEquals("process", stub.type);
        assertEquals("", stub.xml);
        assertSame(tk, stub.pkg);
        assertSame(stub, m.find("1.tk-guid"));
        assertSame(stub, m.find("snap-biz/1.tk-guid"), "Dependency-Referenz snap/obj");
    }

    @Test
    void diagnosticsFromParseErrors() {
        TwxModel m = RefModel.from(parsed());
        assertEquals(1, m.diagnostics.size());
        assertEquals("parse-error", m.diagnostics.get(0).get("code"));
        assertTrue(m.diagnostics.get(0).get("message").toString().contains("not well-formed"));
    }

    @Test
    void embeddedSubProcessGetsActivityNames() {
        TwxModel m = RefModel.from(parsed());
        assertEquals("Do Thing (in Host)", m.app.objects.get("64." + GUID).name);
        assertEquals("Host", m.app.objects.get("64.host").name);
    }
}

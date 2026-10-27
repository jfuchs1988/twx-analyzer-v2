package io.twxav2.api;

import io.quarkus.test.junit.QuarkusTest;
import io.twxav2.model.Snapshot;
import io.twxav2.model.ToolkitSnapshot;
import io.twxav2.support.ApiFixtures;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;

import static io.twxav2.support.ApiFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

/** Toolkit inventory (listing, nesting, deletion) and variable endpoints. */
@QuarkusTest
class ToolkitVariableApiTest {

    @Inject
    ApiFixtures fx;

    @BeforeEach
    void roomOn() {
        fx.wipeAll();
    }

    private Snapshot baseWithToolkits() {
        Snapshot s = fx.snapshot("FIXK", "Kiste", Snapshot.Status.READY);
        ToolkitSnapshot alt = fx.toolkit("2064.t1", "TK1", "v1.0");
        ToolkitSnapshot neu = fx.toolkit("2064.t2", "TK1", "v2.0");
        fx.dependency(s, neu);
        fx.nest(neu, alt);   // TK1 v1.0 is nested and outdated
        return s;
    }

    @Test
    void inventarListung() throws Exception {
        Snapshot s = baseWithToolkits();
        HttpResponse<String> r = get("/api/toolkits");
        assertEquals(200, r.statusCode());
        assertTrue(r.body().contains("2064.t1") && r.body().contains("2064.t2"), r.body());
        assertTrue(r.body().contains("\"childOutdated\":true"), r.body());

        assertEquals(200, get("/api/toolkits?latestOnly=true").statusCode());

        String toolkits = get("/api/snapshots/" + s.id + "/toolkits").body();
        assertTrue(toolkits.contains("TK1"), toolkits);
        assertEquals(200, get("/api/snapshots/" + s.id + "/toolkit-graph").statusCode());
        assertEquals(200, get("/api/snapshots/toolkit-conflicts").statusCode());
    }

    @Test
    void einzelloeschen() throws Exception {
        baseWithToolkits();
        assertEquals(404, delete("/api/toolkits/2064.nix").statusCode());
        assertEquals(204, delete("/api/toolkits/2064.t2").statusCode());
        assertFalse(get("/api/toolkits").body().contains("2064.t2"));
        assertEquals(0, io.twxav2.model.ToolkitNest.count("childToolkitId", "2064.t1"));
    }

    @Test
    void ungenutzteLoeschen() throws Exception {
        baseWithToolkits();
        HttpResponse<String> r = delete("/api/toolkits/unused");
        assertEquals(200, r.statusCode());
        assertTrue(r.body().contains("\"deleted\":1"), r.body());
        assertEquals(0, io.twxav2.model.ToolkitSnapshot.count("id", "2064.t1"),
                "ohne Referenz entfernt");
        assertEquals(1, io.twxav2.model.ToolkitSnapshot.count("id", "2064.t2"),
                "mit Referenz bleibt");
        assertTrue(get("/api/toolkits").body().contains("2064.t2"));
    }

    // ---------------- Variables ----------------

    @Test
    void variablenKatalogUndNutzung() throws Exception {
        Snapshot s = fx.snapshot("FIXV", "Variablen", Snapshot.Status.READY);
        fx.usage(s, "1.abc", "kunde", true);
        fx.usage(s, "1.abc", "kunde", false);
        fx.usage(s, "1.abc", "spesen", false);

        String catalog = get("/api/variables?snapshotId=" + s.id).body();
        assertTrue(catalog.contains("kunde") && catalog.contains("spesen"), catalog);
        assertTrue(get("/api/variables?snapshotId=" + s.id + "&q=SPES").body().contains("spesen"),
                "case-insensitive");
        assertTrue(get("/api/variables?snapshotId=" + s.id + "&scope=local").body().contains("kunde"));

        String usage = get("/api/variables/usage?snapshotId=" + s.id + "&variable=kunde").body();
        assertTrue(usage.contains("Prozess A"), usage);
        assertEquals(2, java.util.regex.Pattern.compile("\"objectId\"").matcher(usage).results().count(),
                "beide kunde-Zeilen");
        assertEquals(2, java.util.regex.Pattern.compile("\"objectId\"").matcher(
                get("/api/variables/usage?snapshotId=" + s.id + "&variable=kunde&scope=local")
                        .body()).results().count(), "Scope-Treffer");
        assertEquals("[]", get("/api/variables/usage?snapshotId=" + s.id
                + "&variable=kunde&scope=fremd").body(), "Scope-Fehltreffer");
        assertEquals("[]", get("/api/variables/usage?variable=kunde").body(), "snapshotId fehlt");
        assertEquals("[]", get("/api/variables/usage?snapshotId=" + s.id).body(), "variable fehlt");
    }

    @Test
    void backfillScanntInhalt() throws Exception {
        Snapshot s = fx.snapshot("FIXB", "Backfill", Snapshot.Status.READY);
        fx.object(s, "1.abc", "1", "Prozess A",
                "<process name=\"Prozess A\"><script name=\"m\">"
                        + "tw.local.kunde = 1;\nvar p = tw.processVariables.port;</script></process>");

        HttpResponse<String> r = postJson("/api/variables/backfill", "{}");
        assertEquals(200, r.statusCode());
        assertTrue(Integer.parseInt(r.body().trim()) >= 1, "rows=" + r.body());

        String catalog = get("/api/variables?snapshotId=" + s.id).body();
        assertTrue(catalog.contains("kunde"), catalog);
    }
}

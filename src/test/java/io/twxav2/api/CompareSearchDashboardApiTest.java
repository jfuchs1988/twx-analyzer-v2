package io.twxav2.api;

import io.quarkus.test.junit.QuarkusTest;
import io.twxav2.model.Finding;
import io.twxav2.model.Snapshot;
import io.twxav2.support.ApiFixtures;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;

import static io.twxav2.support.ApiFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

/** Version comparison, search and dashboard against H2 fixtures. */
@QuarkusTest
class CompareSearchDashboardApiTest {

    @Inject
    ApiFixtures fx;

    @BeforeEach
    void roomOn() {
        fx.wipeAll();
    }

    // ---------------- Compare ----------------

    private Snapshot[] twoVersions() {
        Snapshot base = fx.snapshot("FIXC", "Alt", Snapshot.Status.READY);
        Snapshot target = fx.snapshot("FIXC", "Neu", Snapshot.Status.READY);
        fx.object(base, "1.gleich", "1", "Gleich", "<process name=\"Gleich\"/>");
        fx.object(target, "1.gleich", "1", "Gleich", "<process name=\"Gleich\"/>");
        fx.object(base, "1.changed", "1", "Geaendert", "<process name=\"Geaendert\"/>");
        fx.object(target, "1.changed", "1", "Geaendert", "<process name=\"Geaendert v2\"/>");
        fx.object(base, "1.weg", "1", "Entfernt", "<process name=\"Entfernt\"/>");
        fx.object(target, "1.neu", "1", "Neu", "<process name=\"Neu\"/>");
        fx.finding(base, "R-TST-001 Endpoint-URL", Finding.Severity.MAJOR, null);
        fx.finding(target, "R-TST-001 Endpoint-URL", Finding.Severity.MAJOR, null);
        fx.finding(target, "R-TST-002 Geheimnis", Finding.Severity.MINOR, null);
        fx.bumpCounters(base);
        fx.bumpCounters(target);
        return new Snapshot[] {base, target};
    }

    @Test
    void vergleich() throws Exception {
        Snapshot[] p = twoVersions();
        HttpResponse<String> r = get("/api/compare?base=" + p[0].id + "&target=" + p[1].id);
        assertEquals(200, r.statusCode());
        assertTrue(r.body().contains("\"addedObjects\"") && r.body().contains("Neu"), r.body());
        assertTrue(r.body().contains("Entfernt") && r.body().contains("\"removedObjects\""));
        assertTrue(r.body().contains("Geaendert"), "sha256-Unterschied -> changed");
        assertTrue(r.body().contains("R-TST-002"), "Rule-Deltas");

        assertEquals(400, get("/api/compare?base=" + p[0].id + "&target=" + p[0].id).statusCode());
        assertEquals(400, get("/api/compare?base=" + p[0].id).statusCode());
        assertEquals(404, get("/api/compare?base=" + p[0].id + "&target=999999").statusCode());
    }

    @Test
    void objektDiff() throws Exception {
        Snapshot[] p = twoVersions();
        HttpResponse<String> r = get("/api/compare/object?base=" + p[0].id
                + "&target=" + p[1].id + "&objectId=" + p[0].id + ":1.changed");
        assertEquals(200, r.statusCode());
        assertTrue(r.body().contains("\"additions\""), r.body());

        // one-sided object: only in the target
        HttpResponse<String> nurZiel = get("/api/compare/object?base=" + p[0].id
                + "&target=" + p[1].id + "&objectId=1.neu");
        assertTrue(nurZiel.body().contains("Object exists only in target"), nurZiel.body());

        assertEquals(400, get("/api/compare/object?base=" + p[0].id + "&target=" + p[1].id).statusCode());
        assertEquals(404, get("/api/compare/object?base=" + p[0].id
                + "&target=" + p[1].id + "&objectId=1.nirgends").statusCode());
    }

    // ---------------- Search ----------------

    private Snapshot suchgrundlage() {
        Snapshot s = fx.snapshot("FIXQ", "Suche", Snapshot.Status.READY);
        fx.object(s, "1.abc", "1", "Prozess Suche", "<process name=\"Prozess Suche\">"
                + "<script name=\"m\">var kunde = tw.local.kunde;</script></process>");
        fx.object(s, "2051.rest", "2051", "REST Aussen", "<restService name=\"REST Aussen\"/>");
        return s;
    }

    @Test
    void sucheScopes() throws Exception {
        Snapshot s = suchgrundlage();
        String name = get("/api/search?q=Suche&snapshotId=" + s.id).body();
        assertTrue(name.contains("Prozess Suche"), name);
        assertFalse(name.contains("REST Aussen"));

        String raw = get("/api/search?q=kunde&snapshotId=" + s.id + "&scope=raw").body();
        assertTrue(raw.contains("Prozess Suche"), raw);

        String regex = get("/api/search?q=REST.+&snapshotId=" + s.id + "&regex=true").body();
        assertTrue(regex.contains("REST Aussen"), regex);
    }

    @Test
    void sucheFehler() throws Exception {
        suchgrundlage();
        HttpResponse<String> kurz = get("/api/search?q=x");
        assertEquals(200, kurz.statusCode());
        assertTrue(kurz.body().contains("zu kurz"), kurz.body());

        assertEquals(400, get("/api/search?q=abcdef&scope=blabla").statusCode());
        assertEquals(400, get("/api/search?q=[&regex=true").statusCode());
    }

    // ---------------- Dashboard ----------------

    @Test
    void dashboardAggregiert() throws Exception {
        fx.snapshot("FIXD", "Erste", Snapshot.Status.READY);
        Snapshot neuste = fx.snapshot("FIXD", "Zweite", Snapshot.Status.READY);
        fx.finding(neuste, "R-TST-001 Endpoint-URL", Finding.Severity.CRITICAL, null);

        HttpResponse<String> r = get("/api/dashboard");
        assertEquals(200, r.statusCode());
        assertTrue(r.body().contains("\"acronym\":\"FIXD\""), r.body());
        assertTrue(r.body().contains("\"openCritical\":1"),
                "Critical des neuesten Snapshots: " + r.body());
    }

    /** Tile size metrics: services/BPDs/JS lines are taken from the latest snapshot only. */
    @Test
    void dashboardGroessenMetriken() throws Exception {
        Snapshot alt = fx.snapshot("FIXG", "Alt", Snapshot.Status.READY);
        fx.object(alt, "1.svc", "1", "service", "Alt-Svc", "<process name=\"Alt-Svc\"/>");
        fx.scriptLines(alt, 500);
        Snapshot neu = fx.snapshot("FIXG", "Neu", Snapshot.Status.READY);
        fx.object(neu, "1.svc1", "1", "service", "Svc 1", "<process name=\"Svc 1\"/>");
        fx.object(neu, "1.svc2", "1", "service", "Svc 2", "<process name=\"Svc 2\"/>");
        fx.object(neu, "1.cshs", "1", "cshs", "CSHS", "<process name=\"CSHS\"/>");
        fx.object(neu, "25.bpd1", "25", "bpd", "Prozess 1", "<bpd name=\"Prozess 1\"/>");
        fx.object(neu, "25.bpd2", "25", "subBpd", "Embedded", "<bpd name=\"Embedded\"/>");
        fx.scriptLines(neu, 12345);

        HttpResponse<String> r = get("/api/dashboard");
        assertEquals(200, r.statusCode());
        String body = r.body();
        assertTrue(body.contains("\"serviceCount\":2"), "2 Services (CSHS und subBpd nicht gezaehlt): " + body);
        assertTrue(body.contains("\"bpdCount\":1"), "nur Top-Level-BPD: " + body);
        assertTrue(body.contains("\"scriptLines\":12345"), "JS-Zeilen des neuesten Snapshots: " + body);
    }
}

package io.twxav2.service;

import io.quarkus.test.TestTransaction;
import io.quarkus.test.common.http.TestHTTPResourceManager;
import io.quarkus.test.junit.QuarkusTest;
import io.twxav2.model.Finding;
import io.twxav2.model.Snapshot;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.*;

/**
 * @QuarkusTest reference access: test profile with H2, schema from entities,
 * Panache round trip and one opened REST endpoint via the test port.
 * Keeps the boot gate clear for all further DB tests.
 */
@QuarkusTest
class DbSmokeTest {

    @Test
    @TestTransaction
    void snapshotWithFindingRun() {
        Snapshot s = new Snapshot();
        s.filename = "demo.twx";
        s.projectName = "Demo";
        s.projectAcronym = "DEMO";
        s.snapshotName = "v1.0";
        s.status = Snapshot.Status.READY;
        s.persist();

        Finding f = new Finding();
        f.snapshot = s;
        f.ruleId = "R-TST-001 Hardcodierte Endpoint-URL";
        f.severity = Finding.Severity.MAJOR;
        f.score = 40;
        f.persist();

        assertEquals(1, Finding.count("snapshot = ?1", s));
        Finding geladen = Finding.<Finding>find("snapshot = ?1", s).firstResult();
        assertEquals(40, geladen.score);
        assertEquals(Finding.Severity.MAJOR, geladen.severity);
    }

    @Test
    void offenerListenEndpunktOhneToken() throws Exception {
        String base = TestHTTPResourceManager.getUri();
        HttpResponse<String> r = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(base + "/api/snapshots")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, r.statusCode());
        assertTrue(r.body().trim().startsWith("["), "Erwartet JSON-Liste, kam: " + r.body());
    }
}

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

/** Single and bulk status changes (B5) via REST. */
@QuarkusTest
class FindingStatusApiTest {

    @Inject
    ApiFixtures fx;

    @BeforeEach
    void roomOn() {
        fx.wipeAll();
    }

    @Test
    void einzelStatus() throws Exception {
        Snapshot s = fx.snapshot("FIXS", "Status", Snapshot.Status.READY);
        Finding f = fx.finding(s, "R-TST-001 Endpoint-URL", Finding.Severity.MAJOR, null);

        HttpResponse<String> ok = putJson("/api/findings/" + f.id + "/status",
                "{\"status\":\"RESOLVED\",\"comment\":\"geprueft, ok\"}");
        assertEquals(200, ok.statusCode());
        assertTrue(ok.body().contains("RESOLVED") && ok.body().contains("geprueft, ok"), ok.body());

        String liste = get("/api/snapshots/" + s.id + "/findings?status=RESOLVED").body();
        assertTrue(liste.contains("R-TST-001"));

        // lowercase + whitespace tolerated
        assertEquals(200, putJson("/api/findings/" + f.id + "/status",
                "{\"status\":\" accepted \"}").statusCode());

        assertEquals(400, putJson("/api/findings/" + f.id + "/status",
                "{\"status\":\"ERLEDIGT\"}").statusCode());
        assertEquals(404, putJson("/api/findings/999999/status",
                "{\"status\":\"OPEN\"}").statusCode());
    }

    @Test
    void commentGetsTruncated() throws Exception {
        Snapshot s = fx.snapshot("FIXK", "Kommentar", Snapshot.Status.READY);
        Finding f = fx.finding(s, "R-TST-002 Geheimnis", Finding.Severity.MINOR, null);
        String lang = "x".repeat(1100);
        HttpResponse<String> r = putJson("/api/findings/" + f.id + "/status",
                "{\"status\":\"ACCEPTED\",\"comment\":\"" + lang + "\"}");
        assertEquals(200, r.statusCode());
        assertTrue(r.body().contains("x".repeat(1000)), "1000 Zeichen vorhanden");
        assertFalse(r.body().contains("x".repeat(1001)), "bei 1000 gekappt");
    }

    @Test
    void masseStatus() throws Exception {
        Snapshot s = fx.snapshot("FIXM", "Masse", Snapshot.Status.READY);
        fx.finding(s, "R-TST-010 Kleiner Stoss", Finding.Severity.MINOR, null);
        fx.finding(s, "R-TST-020 Kleiner Kratzer", Finding.Severity.MINOR, null);
        fx.finding(s, "R-TST-030 Grosser Schaden", Finding.Severity.CRITICAL, null);

        HttpResponse<String> minor = putJson("/api/snapshots/" + s.id + "/findings/status",
                "{\"status\":\"ACCEPTED\",\"severity\":\"minor\"}");
        assertEquals(200, minor.statusCode());
        assertTrue(minor.body().contains("\"changed\":2"), minor.body());

        HttpResponse<String> reg = putJson("/api/snapshots/" + s.id + "/findings/status",
                "{\"status\":\"RESOLVED\",\"rule\":\"R-TST-030\"}");
        assertTrue(reg.body().contains("\"changed\":1"), reg.body());

        HttpResponse<String> alle = putJson("/api/snapshots/" + s.id + "/findings/status",
                "{\"status\":\"OPEN\"}");
        assertTrue(alle.body().contains("\"changed\":3"), alle.body());

        assertEquals(404, putJson("/api/snapshots/999999/findings/status",
                "{\"status\":\"OPEN\"}").statusCode());
        assertEquals(400, putJson("/api/snapshots/" + s.id + "/findings/status",
                "{\"status\":\"FERTIG\"}").statusCode());
        assertEquals(400, putJson("/api/snapshots/" + s.id + "/findings/status",
                "{\"status\":\"OPEN\",\"severity\":\"BOESE\"}").statusCode());
    }
}

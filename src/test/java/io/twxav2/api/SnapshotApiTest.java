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

/** Read + write endpoints of SnapshotResource against H2 fixtures. */
@QuarkusTest
class SnapshotApiTest {

    @Inject
    ApiFixtures fx;

    @BeforeEach
    void roomOn() {
        fx.wipeAll();
    }

    private Snapshot demoSnapshotWithFindings() {
        Snapshot s = fx.snapshot("FIXA", "Alpha", Snapshot.Status.READY);
        fx.object(s, "1.aaa", "1", "Alpha Prozess", "<process name=\"Alpha Prozess\"/>");
        fx.object(s, "1.bbb", "1", "Beta Prozess", "<process name=\"Beta Prozess\"/>");
        fx.object(s, "2051.ccc", "2051", "REST X", "<restService name=\"REST X\"/>");
        fx.finding(s, "R-TST-100 Kritischer Befund", Finding.Severity.CRITICAL, Finding.Status.OPEN);
        fx.finding(s, "R-TST-200 Info Befund", Finding.Severity.INFO, Finding.Status.ACCEPTED);
        fx.finding(s, "R-TST-300 Minor Befund", Finding.Severity.MINOR, null);
        fx.bumpCounters(s);
        return s;
    }

    @Test
    void listeUndDetail() throws Exception {
        Snapshot s = demoSnapshotWithFindings();
        HttpResponse<String> list = get("/api/snapshots");
        assertEquals(200, list.statusCode());
        assertTrue(list.body().contains("FIXA"), list.body());
        assertTrue(list.body().contains("\"findingCount\":3"), list.body());

        HttpResponse<String> detail = get("/api/snapshots/" + s.id);
        assertEquals(200, detail.statusCode());
        assertTrue(detail.body().contains("Alpha App"));

        assertEquals(404, get("/api/snapshots/999999").statusCode());
    }

    @Test
    void findingsFilter() throws Exception {
        Snapshot s = demoSnapshotWithFindings();
        String all = get("/api/snapshots/" + s.id + "/findings").body();
        assertTrue(all.contains("R-TST-100") && all.contains("R-TST-200") && all.contains("R-TST-300"));

        String crit = get("/api/snapshots/" + s.id + "/findings?severity=CRITICAL").body();
        assertTrue(crit.contains("R-TST-100") && !crit.contains("R-TST-200"));

        String acc = get("/api/snapshots/" + s.id + "/findings?status=ACCEPTED").body();
        assertTrue(acc.contains("R-TST-200") && !acc.contains("R-TST-100"));

        String offen = get("/api/snapshots/" + s.id + "/findings?status=OPEN").body();
        assertTrue(offen.contains("R-TST-100") && offen.contains("R-TST-300") && !offen.contains("R-TST-200"),
                "Altdaten (status=null) mitzaehlen");

        String prae = get("/api/snapshots/" + s.id + "/findings?rule=R-TST-2").body();
        assertTrue(prae.contains("R-TST-200") && !prae.contains("R-TST-100"));

        assertEquals(400, get("/api/snapshots/" + s.id + "/findings?severity=BOESE").statusCode());
        assertEquals(400, get("/api/snapshots/" + s.id + "/findings?status=QUATSCH").statusCode());
    }

    @Test
    void objekteFilterUndPaginierung() throws Exception {
        Snapshot s = demoSnapshotWithFindings();
        assertEquals(3, anzahl(get("/api/snapshots/" + s.id + "/objects").body()));
        assertTrue(get("/api/snapshots/" + s.id + "/objects?q=Alpha").body().contains("Alpha Prozess"));
        String prefix = get("/api/snapshots/" + s.id + "/objects?prefix=2051").body();
        assertTrue(prefix.contains("REST X") && !prefix.contains("Alpha Prozess"));
        assertEquals(1, anzahl(get("/api/snapshots/" + s.id + "/objects?size=1").body()));
        assertEquals(1, anzahl(get("/api/snapshots/" + s.id + "/objects?page=1&size=2").body()));
        assertTrue(get("/api/snapshots/" + s.id + "/objects?page=-5").statusCode() == 200);

        String types = get("/api/snapshots/" + s.id + "/types").body();
        assertTrue(types.contains("process") && types.contains("restService"), types);
    }

    private static int anzahl(String jsonArray) {
        return (int) (java.util.regex.Pattern.compile("\"id\":").matcher(jsonArray).results().count());
    }

    @Test
    void gate() throws Exception {
        Snapshot s = demoSnapshotWithFindings();
        HttpResponse<String> fail = get("/api/snapshots/" + s.id
                + "/gate?maxCritical=0&maxMajor=-1&minHealth=-1");
        assertEquals(200, fail.statusCode());
        assertTrue(fail.body().contains("\"passed\":false"), fail.body());
        assertTrue(fail.body().contains("Critical findings: 1"),
                "Accepted R-TST-200 contributed: " + fail.body());

        String pass = get("/api/snapshots/" + s.id
                + "/gate?maxCritical=5&maxMajor=-1&minHealth=-1").body();
        assertTrue(pass.contains("\"passed\":true") && pass.contains("\"exitCode\":0"), pass);

        assertEquals(404, get("/api/snapshots/999999/gate").statusCode());
    }

    @Test
    void tasksUndRuleSummary() throws Exception {
        Snapshot s = fx.snapshot("FIXT", "Task", Snapshot.Status.READY);
        fx.finding(s, "R-TST-040 Fehlende documentation", Finding.Severity.MINOR, null);
        fx.finding(s, "R-TST-041 Business Objekt ohne Dokumentation", Finding.Severity.MINOR, null);
        fx.finding(s, "R-TST-042 Platzhaltertext im Schrittnamen", Finding.Severity.MINOR, null);
        fx.finding(s, "R-TST-001 Endpoint-URL", Finding.Severity.MAJOR, null);

        String tasks = get("/api/snapshots/" + s.id + "/tasks").body();
        assertTrue(tasks.contains("R-TST-040"), tasks);
        assertTrue(tasks.contains("R-TST-041"), "The German title 'Dokumentation' is included: " + tasks);
        assertTrue(tasks.contains("R-TST-042"), "de-Titel 'Platzhalter' zaehlt mit: " + tasks);
        assertFalse(tasks.contains("R-TST-001"), "nur Doku-/TODO-Regeln, nicht R-TST-001");

        HttpResponse<String> summary = get("/api/snapshots/rule-summary");
        assertEquals(200, summary.statusCode());
        assertTrue(summary.body().contains("\"R-TST-001"), summary.body());
        assertTrue(summary.body().contains(String.valueOf(s.id)) || summary.body().contains("\"R-TST-040"));
    }

    @Test
    void ausschliesszyklus() throws Exception {
        Snapshot s = demoSnapshotWithFindings();
        HttpResponse<String> put = putJson("/api/snapshots/" + s.id + "/exclusions",
                "{\"objectId\":\"1.bbb\"}");
        assertEquals(200, put.statusCode());
        assertTrue(put.body().contains("\"MANUAL\""), put.body());

        String list = get("/api/snapshots/" + s.id + "/exclusions").body();
        assertTrue(list.contains("1.bbb"), list);

        String objects = get("/api/snapshots/" + s.id + "/objects?q=Beta").body();
        assertTrue(objects.contains("\"excluded\":true"), objects);

        HttpResponse<String> again = putJson("/api/snapshots/" + s.id + "/exclusions",
                "{\"objectId\":\"1.bbb\"}");
        assertEquals(200, again.statusCode(), "idempotent");

        assertEquals(204, delete("/api/snapshots/" + s.id + "/exclusions/1.bbb").statusCode());
        assertEquals(404, delete("/api/snapshots/" + s.id + "/exclusions/1.bbb").statusCode());
        assertEquals(404, putJson("/api/snapshots/999999/exclusions",
                "{\"objectId\":\"x\"}").statusCode());
        assertEquals(404, putJson("/api/snapshots/" + s.id + "/exclusions",
                "{\"objectId\":\"9.zzz\"}").statusCode());
    }

    @Test
    void snapshotLoeschen() throws Exception {
        Snapshot s = demoSnapshotWithFindings();
        assertEquals(204, delete("/api/snapshots/" + s.id).statusCode());
        assertEquals(404, get("/api/snapshots/" + s.id).statusCode());
        assertEquals(404, delete("/api/snapshots/" + s.id).statusCode());
        assertEquals(0, Finding.count("snapshot.id", s.id));
        assertEquals(0, io.twxav2.model.TwxObject.count("snapshot.id", s.id));
    }
}

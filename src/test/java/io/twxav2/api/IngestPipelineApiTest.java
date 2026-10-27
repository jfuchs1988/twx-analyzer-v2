package io.twxav2.api;

import io.quarkus.test.junit.QuarkusTest;
import io.twxav2.support.ApiFixtures;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;

import static io.twxav2.support.ApiFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end over the real HTTP paths: upload -> queue -> parser -> rules,
 * reanalysis, invalid input, ingest status, deletion.
 */
@QuarkusTest
class IngestPipelineApiTest {

    @Inject
    ApiFixtures fx;

    @BeforeEach
    void roomOn() {
        fx.wipeAll();
    }

    /** Waits until the snapshot with this file name reaches its END state; returns a body excerpt. */
    private String warteAufStatus(String fileName) throws Exception {
        for (int i = 0; i < 400; i++) {
            String body = get("/api/snapshots").body();
            int at = body.indexOf("\"" + fileName + "\"");
            if (at >= 0) {
                String umgebung = body.substring(at, Math.min(body.length(), at + 900));
                if (umgebung.contains("\"status\":\"READY\"")
                        || umgebung.contains("\"status\":\"ERROR\"")) {
                    return umgebung;
                }
            }
            Thread.sleep(150);
        }
        fail("Timeout beim Warten auf " + fileName);
        return null;
    }

    @Test
    void uploadAnalyseLoeschzyklus() throws Exception {
        String file = "pipeline-" + System.nanoTime() + ".twx";
        HttpResponse<String> up = postMultipart("/api/snapshots", file, ApiFixtures.demoTwx("v9.9"));
        assertEquals(202, up.statusCode(), up.body());
        assertTrue(up.body().contains("taskId"), up.body());

        String fertig = warteAufStatus(file);
        assertTrue(fertig.contains("\"status\":\"READY\""), fertig);
        assertTrue(fertig.contains("FIXP") && fertig.contains("v9.9"), fertig);

        long id = snapshotId(file);
        String objects = get("/api/snapshots/" + id + "/objects").body();
        assertTrue(objects.contains("Prozess A") && objects.contains("REST A"), objects);
        assertTrue(get("/api/snapshots/" + id + "/findings").statusCode() == 200);
        assertTrue(get("/api/snapshots/" + id + "/types").body().contains("process"));

        // toolkit inventory from the embedded ZIP + declared dependency
        String inv = get("/api/toolkits").body();
        assertTrue(inv.contains("TK1"), inv);

        // reanalysis: re-upload the same file from the archive
        HttpResponse<String> re = postJson("/api/snapshots/" + id + "/reanalyze", "");
        assertEquals(202, re.statusCode(), re.body());
        assertEquals("READY", warteAufReady(file, id));
        long reanalyzedId = snapshotId(file);
        assertNotEquals(id, reanalyzedId);
        id = reanalyzedId;

        assertEquals(200, get("/api/ingest/status").statusCode());

        assertEquals(204, delete("/api/snapshots/" + id).statusCode());
        assertEquals(404, get("/api/snapshots/" + id).statusCode());
    }

    @Test
    void invalidFileBecomesError() throws Exception {
        String file = "kaputt-" + System.nanoTime() + ".twx";
        HttpResponse<String> up = postMultipart("/api/snapshots", file,
                "das hier ist kein twx".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertEquals(202, up.statusCode(), up.body());
        String zustand = warteAufStatus(file);
        assertTrue(zustand.contains("\"status\":\"ERROR\""), zustand);
    }

    @Test
    void reanalyzeFehlerPfade() throws Exception {
        HttpResponse<String> unbekannt = postJson("/api/snapshots/999999/reanalyze", "");
        assertEquals(404, unbekannt.statusCode());

        io.twxav2.model.Snapshot s = fx.snapshot("FIXZ", "OhneArchiv",
                io.twxav2.model.Snapshot.Status.READY);
        HttpResponse<String> ohneArchiv = postJson("/api/snapshots/" + s.id + "/reanalyze", "");
        assertEquals(409, ohneArchiv.statusCode(), "ohne Archivdatei nicht erneut analysierbar");
    }

    @Test
    void gleicherDateinameZweimalErsetzenOhneVerlust() throws Exception {
        String file = "doppelt-" + System.nanoTime() + ".twx";
        byte[] twx = ApiFixtures.demoTwx("v9.9");
        assertEquals(202, postMultipart("/api/snapshots", file, twx).statusCode());
        assertTrue(warteAufStatus(file).contains("\"status\":\"READY\""));
        long erster = snapshotId(file);

        // re-upload of the same file: replace-import. The predecessor (READY)
        // stays in place until the ingestion completes; a preceding delete would let
        // the job fail earlier on transaction isolation (data loss).
        HttpResponse<String> up = postMultipart("/api/snapshots", file, twx);
        assertEquals(202, up.statusCode(), up.body());
        assertTrue(up.body().contains("\"replacedSnapshotId\":" + erster), up.body());
        assertTrue(warteAufReady(file, erster).contains("READY"));
        long zweiter = snapshotId(file);
        assertNotEquals(erster, zweiter, "Ersetzen-Import erzeugt neuen Snapshot");

        String nachher = get("/api/snapshots").body();
        assertEquals(1, nachher.split("\"filename\":\"" + java.util.regex.Pattern.quote(file) + "\"").length - 1,
                "genau ein Snapshot je Dateiname: " + nachher);
        String status = get("/api/ingest/status").body();
        // the status endpoint lists the global in-memory task history, which other
        // test classes also populate (intentional ERROR uploads) - assert own tasks only
        for (String task : status.split("\\},\\s*\\{")) {
            if (task.contains("\"label\":\"" + file + "\"")) {
                assertFalse(task.contains("\"phase\":\"ERROR\""), "eigener Task fehlerhaft: " + task);
            }
        }

        assertEquals(204, delete("/api/snapshots/" + zweiter).statusCode());
    }

    /** Wait for READY of the successor: ignores the READY predecessor with oldId. */
    private String warteAufReady(String fileName, long oldId) throws Exception {
        for (int i = 0; i < 400; i++) {
            String body = get("/api/snapshots").body();
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("\"id\":(\\d+),\"filename\":\"" + java.util.regex.Pattern.quote(fileName)
                            + "\"[^}]*\"status\":\"(READY|ERROR)\"")
                    .matcher(body);
            while (m.find()) {
                if (Long.parseLong(m.group(1)) != oldId) {
                    return m.group(2);
                }
            }
            Thread.sleep(150);
        }
        fail("Timeout beim Warten auf Nachfolger von #" + oldId);
        return null;
    }

    private long snapshotId(String fileName) throws Exception {
        String body = get("/api/snapshots").body();
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"id\":(\\d+),\"filename\":\"" + java.util.regex.Pattern.quote(fileName) + "\"")
                .matcher(body);
        assertTrue(m.find(), "Snapshot in Liste nicht gefunden: " + body);
        return Long.parseLong(m.group(1));
    }
}

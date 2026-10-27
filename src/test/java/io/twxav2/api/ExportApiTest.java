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

/** JSON/CSV/HTML export incl. status semantics and error paths. */
@QuarkusTest
class ExportApiTest {

    @Inject
    ApiFixtures fx;

    @BeforeEach
    void roomOn() {
        fx.wipeAll();
    }

    private Snapshot demo() {
        Snapshot s = fx.snapshot("FIXE", "Export", Snapshot.Status.READY);
        fx.finding(s, "R-TST-001 Endpoint-URL", Finding.Severity.MAJOR, Finding.Status.OPEN);
        fx.finding(s, "R-TST-002 Geheimnis", Finding.Severity.MINOR, Finding.Status.ACCEPTED);
        return s;
    }

    @Test
    void jsonExport() throws Exception {
        Snapshot s = demo();
        HttpResponse<String> eff = get("/api/export/" + s.id);
        assertEquals(200, eff.statusCode());
        assertTrue(eff.body().contains("R-TST-001") && !eff.body().contains("R-TST-002"),
                "effective blendet akzeptierte aus: " + eff.body());
        assertTrue(eff.body().contains("\"count\":1"));

        assertTrue(get("/api/export/" + s.id + "?status=all").body().contains("R-TST-002"));
        assertFalse(get("/api/export/" + s.id + "?status=open").body().contains("R-TST-002"));
        assertEquals(400, get("/api/export/" + s.id + "?status=opne").statusCode());
        assertEquals(404, get("/api/export/999999").statusCode());
    }

    @Test
    void csvExport() throws Exception {
        Snapshot s = demo();
        HttpResponse<String> csv = get("/api/export/" + s.id + "/csv");
        assertEquals(200, csv.statusCode());
        assertTrue(csv.body().contains("Rule;Severity"), csv.body());
        assertTrue(csv.body().contains("R-TST-001"));
        assertTrue(csv.body().startsWith("﻿"), "UTF-8-BOM");
        assertEquals(400, get("/api/export/" + s.id + "/csv?status=quatsch").statusCode());
        assertEquals(404, get("/api/export/999999/csv").statusCode());
    }

    @Test
    void csvExportNeutralizesFormulaLeadingFields() throws Exception {
        Snapshot s = fx.snapshot("FIXE", "Formula safety", Snapshot.Status.READY);
        Finding f = fx.finding(s, "R-TST-003", Finding.Severity.MAJOR, Finding.Status.OPEN);
        fx.formulaLeadingFinding(f.id);

        HttpResponse<String> csv = get("/api/export/" + s.id + "/csv");

        assertEquals(200, csv.statusCode());
        for (String value : new String[]{"'=1+1", "'+1+1", "'-1+1", "'@SUM(A1:A2)"}) {
            assertTrue(csv.body().contains(value), "formula-leading value was not neutralized: " + value);
        }
    }

    @Test
    void htmlExport() throws Exception {
        Snapshot s = demo();
        HttpResponse<String> html = get("/api/export/" + s.id + "/html");
        assertEquals(200, html.statusCode());
        assertTrue(html.body().contains("<html"), html.body().substring(0, 100));
        assertTrue(html.body().contains("R-TST-001"));
        assertEquals(400, get("/api/export/" + s.id + "/html?status=x").statusCode());
        assertEquals(404, get("/api/export/999999/html").statusCode());
    }
}

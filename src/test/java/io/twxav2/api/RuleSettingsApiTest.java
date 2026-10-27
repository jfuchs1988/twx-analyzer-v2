package io.twxav2.api;

import io.quarkus.test.junit.QuarkusTest;
import io.twxav2.model.Snapshot;
import io.twxav2.support.ApiFixtures;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;

import static io.twxav2.support.ApiFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

/** RuleSettingsResource: catalog, global and snapshot settings. */
@QuarkusTest
class RuleSettingsApiTest {

    @Inject
    ApiFixtures fx;

    @BeforeEach
    void roomOn() {
        fx.wipeAll();
    }

    @Test
    void katalog() throws Exception {
        HttpResponse<String> r = get("/api/rules");
        assertEquals(200, r.statusCode());
        assertTrue(r.body().startsWith("["));
        assertTrue(r.body().contains("R-APP-001"), "Katalog enthält R-APP-001");
    }

    @Test
    void globaleEinstellungen() throws Exception {
        String leer = get("/api/rules/settings").body();
        assertTrue(leer.contains("\"overrides\"") && !leer.contains("R-TST-001"), leer);

        assertEquals(204, putJson("/api/rules/settings",
                "{\"overrides\":{\"R-TST-001\":{\"enabled\":false}}}").statusCode());
        String echo = get("/api/rules/settings").body();
        assertTrue(echo.contains("R-TST-001") && echo.contains("false"), echo);

        assertEquals(400, putJson("/api/rules/settings",
                "{\"overrides\":{\"R-TST-001\":{\"impact\":5}}}").statusCode());
        assertEquals(400, putJson("/api/rules/settings",
                "{\"overrides\":{\"R-TST-001\":{\"cap\":-1}}}").statusCode());
        assertEquals(400, putJson("/api/rules/settings",
                "{\"overrides\":{\"R-TST-001\":{\"enabled\":\"JA\"}}}").statusCode());

        HttpResponse<String> defaults = get("/api/rules/settings/defaults");
        assertEquals(200, defaults.statusCode());
        assertTrue(defaults.body().contains("thresholds") || defaults.body().contains("overrides"),
                defaults.body());
    }

    @Test
    void snapshotEinstellungen() throws Exception {
        Snapshot s = fx.snapshot("FIXR", "Regeln", Snapshot.Status.READY);
        assertTrue(get("/api/rules/settings/snapshot/" + s.id).body().contains("\"overrides\""));

        assertEquals(204, putJson("/api/rules/settings/snapshot/" + s.id,
                "{\"overrides\":{\"R-TST-010\":{\"impact\":1}}}").statusCode());
        assertTrue(get("/api/rules/settings/snapshot/" + s.id).body().contains("R-TST-010"));

        assertEquals(400, putJson("/api/rules/settings/snapshot/" + s.id,
                "kein json").statusCode());

        assertEquals(204, delete("/api/rules/settings/snapshot/" + s.id).statusCode());
        assertFalse(get("/api/rules/settings/snapshot/" + s.id).body().contains("R-TST-010"),
                "nach DELETE wieder Standard");
    }
}

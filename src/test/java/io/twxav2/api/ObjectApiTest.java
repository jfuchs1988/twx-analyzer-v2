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

/** ObjectResource: content, detail, scripts, diagram — incl. 404 paths. */
@QuarkusTest
class ObjectApiTest {

    private static final String PROZESS_XML = """
            <process name="Prozess A" description="Demo">
              <script name="main">var kunde = tw.local.kunde; log.info(kunde);</script>
            </process>""";

    @Inject
    ApiFixtures fx;

    @BeforeEach
    void roomOn() {
        fx.wipeAll();
    }

    private Snapshot withObject() {
        Snapshot s = fx.snapshot("FIXO", "Objekt", Snapshot.Status.READY);
        fx.object(s, "1.abc", "1", "Prozess A", PROZESS_XML);
        return s;
    }

    @Test
    void inhalt() throws Exception {
        Snapshot s = withObject();
        HttpResponse<String> r = get("/api/objects/" + s.id + ":1.abc");
        assertEquals(200, r.statusCode());
        assertTrue(r.body().contains("tw.local.kunde"), r.body());
        assertTrue(r.body().contains("\"typeName\":\"process\""), r.body());

        assertEquals(404, get("/api/objects/9999:1.nix").statusCode());
    }

    @Test
    void detail() throws Exception {
        Snapshot s = withObject();
        HttpResponse<String> r = get("/api/objects/" + s.id + ":1.abc/detail");
        assertEquals(200, r.statusCode());
        assertTrue(r.body().contains("Prozess A"), r.body());
        assertEquals(404, get("/api/objects/9999:1.nix/detail").statusCode());
    }

    @Test
    void scripts() throws Exception {
        Snapshot s = withObject();
        HttpResponse<String> r = get("/api/objects/" + s.id + ":1.abc/scripts");
        assertEquals(200, r.statusCode());
        assertTrue(r.body().contains("tw.local.kunde"), r.body());

        // object without content -> empty list, not an error
        fx.object(s, "1.leer", "1", "Ohne Inhalt", null);
        assertEquals("[]", get("/api/objects/" + s.id + ":1.leer/scripts").body());
        assertEquals(404, get("/api/objects/9999:1.nix/scripts").statusCode());
    }

    @Test
    void diagramm() throws Exception {
        Snapshot s = withObject();
        HttpResponse<String> r = get("/api/objects/" + s.id + ":1.abc/diagram");
        assertEquals(200, r.statusCode());
        // no BPMN diagram in the process XML -> empty node/edge lists
        assertTrue(r.body().contains("\"nodes\":[]"), r.body());
        assertEquals(404, get("/api/objects/9999:1.nix/diagram").statusCode());
    }
}

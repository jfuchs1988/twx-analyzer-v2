package io.twxav2.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Entity initializers without a DB (the agent does not instrument in the Quarkus lane, see pom):
 * default values and enum members.
 */
class ModelEntitiesTest {

    @Test
    void snapshotDefaultwerte() {
        Snapshot s = new Snapshot();
        assertNull(s.id);
        assertEquals(Snapshot.Status.UPLOADED, s.status);
        assertNotNull(s.uploadedAt);
        assertEquals(4, Snapshot.Status.values().length);
        assertEquals(Snapshot.Status.READY, Snapshot.Status.valueOf("READY"));
    }

    @Test
    void bpmSettingsFesteId() {
        BpmSettings b = new BpmSettings();
        assertEquals(1L, b.id);
        assertNull(b.baseUrl);
    }
}

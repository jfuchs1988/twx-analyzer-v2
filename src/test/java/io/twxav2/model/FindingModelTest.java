package io.twxav2.model;

import io.twxav2.model.Finding.Severity;
import io.twxav2.model.Finding.Status;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Pure model mechanics without a database: score weights and status enum. */
class FindingModelTest {

    @Test
    void heavyWeights() {
        assertEquals(100, Finding.weight(Severity.CRITICAL));
        assertEquals(40, Finding.weight(Severity.MAJOR));
        assertEquals(10, Finding.weight(Severity.MINOR));
        assertEquals(2, Finding.weight(Severity.INFO));
    }

    @Test
    void statusEnum() {
        assertEquals(3, Status.values().length);
        assertEquals(Status.OPEN, Status.valueOf("OPEN"));
        assertEquals(Status.RESOLVED, Status.valueOf("RESOLVED"));
        assertEquals(Status.ACCEPTED, Status.valueOf("ACCEPTED"));
    }

    @Test
    void entityWithoutDbLineCreatable() {
        var f = new Finding();
        f.ruleId = "R-TST-001";
        f.severity = Severity.MINOR;
        f.status = Status.ACCEPTED;
        assertEquals("R-TST-001", f.ruleId);
        assertEquals(Status.ACCEPTED, f.status);
    }
}

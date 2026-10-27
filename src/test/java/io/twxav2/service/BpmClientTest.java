package io.twxav2.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.twxav2.service.BpmClient.SnapshotInfo;
import java.util.List;
import org.junit.jupiter.api.Test;

/** filterVersions: all named snapshots plus TIP — TIP is always the latest state. */
class BpmClientTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static List<SnapshotInfo> filter(String json) throws Exception {
        return BpmClient.filterVersions(MAPPER.readTree(json));
    }

    @Test
    void namedSnapshotsAlwaysListed() throws Exception {
        String json = """
                { "versions": [
                  { "version": "V2.5.8", "version_name": "v2.5.8", "creation_date": "2026-10-01", "branch_name": "Main" },
                  { "version": "V2.5.9", "version_name": "v2.5.9", "creation_date": "2026-10-20", "branch_name": "Main", "active": true }
                ] }""";
        List<SnapshotInfo> out = filter(json);
        assertEquals(2, out.size());
        assertTrue(out.stream().anyMatch(s -> s.versionName().equals("v2.5.9") && s.active()));
    }

    @Test
    void tipWirdAlsLetzterStandGelistetAuchOhneBenannteBasis() throws Exception {
        // real case: only v0.0.1 named, TIP 1.0.7 -> TIP must be visible,
        // it is the only current state.
        String json = """
                { "versions": [
                  { "version": "V0.0.1", "version_name": "v0.0.1", "creation_date": "2020-04-21", "branch_name": "Main" },
                  { "version": "V1.0.7", "version_name": "V1.0.7", "creation_date": "2026-06-19", "branch_name": "Main", "tip": true }
                ] }""";
        List<SnapshotInfo> out = filter(json);
        assertEquals(2, out.size());
        SnapshotInfo tip = out.stream().filter(SnapshotInfo::tip).findFirst().orElseThrow();
        assertEquals("V1.0.7", tip.version());
        assertFalse(out.stream().filter(s -> !s.tip()).findFirst().orElseThrow().tip());
    }

    @Test
    void tipWirdAuchBeiNamensgleichheitMitBenanntemSnapshotGelistet() throws Exception {
        String json = """
                { "versions": [
                  { "version": "V2.5.9", "version_name": "v2.5.9", "creation_date": "2026-10-20", "branch_name": "Main" },
                  { "version": "2.5.9abc", "version_name": "v2.5.9", "creation_date": "2026-10-21", "branch_name": "Main", "tip": true }
                ] }""";
        List<SnapshotInfo> out = filter(json);
        assertEquals(2, out.size());
        assertTrue(out.stream().anyMatch(SnapshotInfo::tip));
    }

    @Test
    void tipOhneNamenBleibtSichtbar() throws Exception {
        String json = """
                { "versions": [
                  { "version": "10001", "version_name": "", "creation_date": "2026-10-22", "branch_name": "Main", "tip": true }
                ] }""";
        List<SnapshotInfo> out = filter(json);
        assertEquals(1, out.size());
        assertTrue(out.get(0).tip());
    }

    @Test
    void unnamedEntriesSkipped() throws Exception {
        String json = """
                { "versions": [
                  { "version": "V9.9.9", "version_name": "V9.9.9", "creation_date": "2026-01-01" },
                  { "version": "8.8.8x", "version_name": "", "creation_date": "2026-01-02" },
                  { "version": "V1.0.0", "version_name": "v1.0.0", "creation_date": "2026-01-03" }
                ] }""";
        List<SnapshotInfo> out = filter(json);
        assertEquals(1, out.size());
        assertEquals("v1.0.0", out.get(0).versionName());
    }

    @Test
    void emptyVersionsEmptyList() throws Exception {
        assertEquals(0, filter("{ \"versions\": [] }").size());
    }
}

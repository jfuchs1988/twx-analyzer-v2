package io.twxav2.analysis.ref;

import io.twxav2.analysis.ref.model.TwxModel;
import io.twxav2.ingest.TwxParseResult;
import io.twxav2.ingest.TwxParseResult.Dependency;
import io.twxav2.ingest.TwxParseResult.Manifest;
import io.twxav2.ingest.TwxParseResult.ParsedObject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Payload extraction of managed assets (jsFiles) and renaming of embedded BPDs. */
class RefModelPayloadTest {

    private static final String GUID = "22222222-3333-4444-5555-666666666666";
    private static final String GUID2 = "33333333-4444-5555-6666-777777777777";

    private static TwxParseResult parsed() {
        return new TwxParseResult(
                new Manifest("Demo", "DEMO", "", "Main", "1.0", "snap", "2026-01-01",
                        "8.6.9", "", false, List.of()),
                List.of(
                        new ParsedObject("61.util", "61", "managedAsset", "util.js", "d", "sha1",
                                "<managedAsset><assetTypeCode>J</assetTypeCode></managedAsset>\n\n"
                                        + "===== Payload util.js =====\nfunction globalUtil() {}\n\n"
                                        + "===== Payload lib2.js =====\nvar second = 1;"),
                        new ParsedObject("61.leer", "61", "managedAsset", "leer.js", "d", "sha2",
                                "<managedAsset><assetTypeCode>J</assetTypeCode></managedAsset>"),
                        new ParsedObject("64." + GUID, "64", "bpd", GUID, null, "sha3",
                                "<process name=\"" + GUID + "\"/>"),
                        new ParsedObject("64.zweit", "64", "bpd", GUID2, null, "sha4",
                                "<process/>"),
                        new ParsedObject("64.host", "64", "bpd", "Host", null, "sha5",
                                "<process><flowObject id=\"f1\"><name>Do Thing</name>"
                                        + "<embeddedProcessId>64." + GUID + "</embeddedProcessId>"
                                        + "</flowObject></process>")),
                List.of(), List.of());
    }

    @Test
    void payloadIsToJsFiles() {
        TwxModel m = RefModel.from(parsed());
        String js = m.app.jsFiles.get("61.util");
        assertNotNull(js);
        assertTrue(js.contains("function globalUtil() {}"), js);
        assertTrue(js.contains("var second = 1;"), js);
        assertFalse(js.contains("Payload"), "Markerzeilen abschneiden");
        assertFalse(m.app.jsFiles.containsKey("61.leer"));   // no payload means no jsFiles entry
    }

    @Test
    void embeddedBpdNamesWithoutReferenceStays() {
        TwxModel m = RefModel.from(parsed());
        // the GUID BPD is named after the activity that embeds it
        assertEquals("Do Thing (in Host)", m.app.objects.get("64." + GUID).name);
        // a GUID BPD without an embedding referrer keeps its GUID name
        assertEquals(GUID2, m.app.objects.get("64.zweit").name);
    }
}

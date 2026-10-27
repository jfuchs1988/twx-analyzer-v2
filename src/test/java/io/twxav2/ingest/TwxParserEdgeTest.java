package io.twxav2.ingest;

import io.twxav2.ingest.TwxParseResult.ParsedObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Edge cases of TwxParser: display subtype (processType/parentBpdId), tags from
 * metadata.xml, truncated object IDs (use_enhanced filenames), caps for package.xml
 * and payload merge, plus the asset branch in toolkit ZIPs (stub XML + jsFiles).
 */
class TwxParserEdgeTest {

    @TempDir
    Path dir;

    private Path twx(List<Object[]> entries) throws Exception {
        Path file = dir.resolve("edge.twx");
        try (var out = new ZipOutputStream(Files.newOutputStream(file))) {
            for (Object[] e : entries) {
                out.putNextEntry(new ZipEntry((String) e[0]));
                out.write((byte[]) e[1]);
                out.closeEntry();
            }
        }
        return file;
    }

    private static Object[] entry(String name, byte[] data) {
        return new Object[] {name, data};
    }

    private static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static final String PKG = """
            <document buildVersion="8.6.9">
              <target>
                <project name="Demo App" shortName="DEMO" isToolkit="false"/>
                <branch name="Main"/>
                <snapshot name="v1" originalCreationDate="2026-01-01"/>
              </target>
            </document>
            """;

    private static ParsedObject byId(io.twxav2.ingest.TwxParseResult r, String id) {
        return r.objects().stream().filter(o -> o.id().equals(id)).findFirst().orElseThrow();
    }

    // ---------- deriveSubType (package-private, direct) ----------

    @Test
    void processSubtypesAfterProcessType() {
        assertEquals("service", TwxParser.deriveSubType("1.a", "<x><processType>12</processType></x>".getBytes()));
        assertEquals("cshs", TwxParser.deriveSubType("1.a", "<processType>10</processType>".getBytes()));
        assertEquals("externalService", TwxParser.deriveSubType("1.a", "<processType>11</processType>".getBytes()));
        assertEquals("systemProcess", TwxParser.deriveSubType("1.a", "<processType>5</processType>".getBytes()));
        assertNull(TwxParser.deriveSubType("1.a", "<process/>".getBytes()));   // without processType
    }

    @Test
    void bpdSubtypeAfterParentBpdId() {
        assertEquals("bpd", TwxParser.deriveSubType("25.b", "<bpd/>".getBytes()));
        assertEquals("bpd", TwxParser.deriveSubType("25.b", "<parentBpdId>  </parentBpdId>".getBytes()));
        assertEquals("subBpd", TwxParser.deriveSubType("25.b",
                "<parentBpdId type=\"25\">uuid/25.top</parentBpdId>".getBytes()));
        assertNull(TwxParser.deriveSubType("64.c", "<bpd/>".getBytes()));      // different prefix
    }

    // ---------- Tags from metadata.xml ----------

    @Test
    void tagsAusMetadataUndObjektOhneIdUebersprungen() throws Exception {
        String meta = """
                <metadata>
                  <object id="1.abc"><tag>produktiv</tag><tag>  </tag><tag>relevant</tag></object>
                  <object id=""><tag>verwaist</tag></object>
                  <object id="1.zzz"><tag>   </tag></object>
                </metadata>""";
        var r = TwxParser.parse(twx(List.of(
                entry("META-INF/package.xml", utf8(PKG)),
                entry("META-INF/metadata.xml", utf8(meta)),
                entry("objects/1.abc.xml", utf8("<process name=\"A\"/>")),
                entry("objects/1.zzz.xml", utf8("<process name=\"Z\"/>")))));
        assertEquals(List.of("produktiv", "relevant"), byId(r, "1.abc").tags());
        assertTrue(byId(r, "1.zzz").tags().isEmpty());        // only blank tags -> no note
        assertTrue(r.errors().isEmpty());
    }

    @Test
    void withoutMetadataNoTags() throws Exception {
        var r = TwxParser.parse(twx(List.of(
                entry("META-INF/package.xml", utf8(PKG)),
                entry("objects/1.abc.xml", utf8("<process name=\"A\"/>")))));
        assertTrue(byId(r, "1.abc").tags().isEmpty());
    }

    // ---------- Caps ----------

    @Test
    void packageXmlUeberCapWirdAbgelehnt() throws Exception {
        String big = "<document><target>" + " ".repeat(TwxParser.MAX_PACKAGE_XML_BYTES) + "</target></document>";
        var ex = assertThrows(IllegalArgumentException.class,
                () -> TwxParser.parse(twx(List.<Object[]>of(entry("META-INF/package.xml", utf8(big))))));
        assertTrue(ex.getMessage().contains("package.xml ueber"));
    }

    @Test
    void payloadUeberCapKirztDenGesamtinhalt() throws Exception {
        StringBuilder xml = new StringBuilder("<process name=\"Fast-Cap\">");
        while (xml.length() < TwxParser.MAX_CONTENT_BYTES - 100_000) {
            xml.append("<!--p-->");
        }
        xml.append("</process>");
        var r = TwxParser.parse(twx(List.of(
                entry("META-INF/package.xml", utf8(PKG)),
                entry("objects/1.big.xml", utf8(xml.toString())),
                entry("files/1.big/extra.js", utf8("//" + "x".repeat(300_000))))));
        ParsedObject o = byId(r, "1.big");
        assertNotNull(o.content());                            // object itself under cap
        assertEquals(TwxParser.MAX_CONTENT_BYTES, o.content().length());
        assertTrue(o.content().contains("===== Payload extra.js ====="));
    }

    @Test
    void einzeldateiUeberContentCapOhneInhaltUndPayload() throws Exception {
        // files/ payload over MAX_CONTENT_BYTES: readLimited marker -> asText null
        // -> payload dropped entirely; object.xml over cap -> content null, name from headers
        String pad = "x".repeat(TwxParser.MAX_CONTENT_BYTES + 10);
        var r = TwxParser.parse(twx(List.of(
                entry("META-INF/package.xml", utf8(PKG)),
                entry("objects/1.huge.xml", utf8("<process name=\"Riese\">" + pad + "</process>")),
                entry("objects/1.small.xml", utf8("<process name=\"Klein\"/>")),
                entry("files/1.small/win.js", utf8("var ok = 1;")),
                entry("files/1.small/big.js", utf8(pad)))));
        ParsedObject riese = byId(r, "1.huge");
        assertNull(riese.content());                           // cap exceeded -> null
        ParsedObject klein = byId(r, "1.small");
        assertNotNull(klein.content());
        assertTrue(klein.content().contains("var ok = 1;"));
        assertFalse(klein.content().contains("===== Payload big.js")); // over cap -> no attachment
    }

    @Test
    void archiveAndAggregateBudgetsRejectOversizedInputs() throws Exception {
        Path valid = twx(List.of(
                entry("META-INF/package.xml", utf8(PKG)),
                entry("objects/1.a.xml", utf8("<process name=\"A\"/>")),
                entry("objects/1.b.xml", utf8("<process name=\"B\"/>"))));
        long fileBytes = Files.size(valid);

        assertLimit(valid, new TwxParser.Limits(fileBytes - 1, 10, 10, Long.MAX_VALUE,
                Long.MAX_VALUE, 1, TwxParser.MAX_TOOLKIT_BYTES, Long.MAX_VALUE));
        assertLimit(valid, new TwxParser.Limits(Long.MAX_VALUE, 2, 10, Long.MAX_VALUE,
                Long.MAX_VALUE, 1, TwxParser.MAX_TOOLKIT_BYTES, Long.MAX_VALUE));
        assertLimit(valid, new TwxParser.Limits(Long.MAX_VALUE, 10, 1, Long.MAX_VALUE,
                Long.MAX_VALUE, 1, TwxParser.MAX_TOOLKIT_BYTES, Long.MAX_VALUE));
        assertLimit(valid, new TwxParser.Limits(Long.MAX_VALUE, 10, 10, 10,
                Long.MAX_VALUE, 1, TwxParser.MAX_TOOLKIT_BYTES, Long.MAX_VALUE));

        Path content = twx(List.of(
                entry("META-INF/package.xml", utf8(PKG)),
                entry("objects/1.a.xml", utf8("<process name=\"A\"/>"))));
        assertLimit(content, new TwxParser.Limits(Long.MAX_VALUE, 10, 10, Long.MAX_VALUE,
                1, 1, TwxParser.MAX_TOOLKIT_BYTES, Long.MAX_VALUE));
    }

    private static void assertLimit(Path file, TwxParser.Limits limits) {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> TwxParser.parse(file, limits));
        assertTrue(error.getMessage().contains("TWX"), error.getMessage());
    }

    @Test
    void withoutPackageXmlNotValidTwx() throws Exception {
        var ex = assertThrows(IllegalArgumentException.class,
                () -> TwxParser.parse(twx(List.<Object[]>of(entry("objects/1.a.xml", utf8("<process/>"))))));
        assertTrue(ex.getMessage().contains("Kein META-INF/package.xml"));
    }

    @Test
    void directoryEintraegeImToolkitWerdenUebersprungen() throws Exception {
        String inner = """
                <document><target>
                  <project name="Kit" shortName="KIT"/><snapshot name="1.0"/>
                  <objects><object id="61.util" name="util.js" type="managedAsset"/></objects>
                </target></document>""";
        var tk = toolkitWith(inner, List.of(
                entry("objects/61.util.xml", utf8(
                        "<managedAsset name=\"util.js\"><assetTypeCode>J</assetTypeCode></managedAsset>")),
                entry("files/61.util/sub/", new byte[0]),                       // directory entry
                entry("files/61.util/util.js", utf8("function g() {}"))));
        assertEquals("function g() {}", tk.jsFiles().get("61.util"));
    }

    // ---------- use_enhanced filenames: long stems ----------

    @Test
    void longNamesShortenedWithLowCollisionAndPayloadMapping() throws Exception {
        String longStem = "1." + "abcdef0123456789".repeat(6);          // 98 chars > 58
        var r = TwxParser.parse(twx(List.of(
                entry("META-INF/package.xml", utf8(PKG)),
                entry("objects/" + longStem + ".xml", utf8("<process name=\"Lang\"/>")),
                entry("files/" + longStem + "/code.js", utf8("var marker = 1;")))));
        ParsedObject o = r.objects().get(0);
        assertEquals("Lang", o.name());
        assertTrue(o.id().startsWith("1."), o.id());
        assertEquals(42, o.id().length());                     // prefix + "." + 40 hex
        assertTrue(o.content().contains("var marker = 1;"));   // payload finds the short form
    }

    // ---------- Toolkit-Assets (Stub-XML + jsFiles) ----------

    private io.twxav2.ingest.TwxParseResult.ParsedToolkit toolkitWith(String innerPkg,
            List<Object[]> extra) throws Exception {
        ByteArrayOutputStream tkBuf = new ByteArrayOutputStream();
        try (var z = new ZipOutputStream(tkBuf)) {
            z.putNextEntry(new ZipEntry("META-INF/package.xml"));
            z.write(utf8(innerPkg));
            z.closeEntry();
            for (Object[] e : extra) {
                z.putNextEntry(new ZipEntry((String) e[0]));
                z.write((byte[]) e[1]);
                z.closeEntry();
            }
        }
        var r = TwxParser.parse(twx(List.of(
                entry("META-INF/package.xml", utf8(PKG)),
                entry("toolkits/tk.zip", tkBuf.toByteArray()))));
        assertTrue(r.errors().isEmpty(), r.errors().toString());
        return r.toolkits().get(0);
    }

    @Test
    void toolkitAssetBekommtStubXmlUndJsFileInhalt() throws Exception {
        String inner = """
                <document><target>
                  <project name="Kit" shortName="KIT"/><snapshot name="1.0"/>
                  <objects>
                    <object id="61.util" name="util.js" type="managedAsset"/>
                    <object id="61.leer" name="leer.js" type="managedAsset"/>
                    <object id="64.cv" name="CV" type="coachView"/>
                  </objects>
                </target></document>""";
        var tk = toolkitWith(inner, List.of(
                entry("objects/61.util.xml", utf8(
                        "<managedAsset name=\"util.js\"><assetTypeCode>J</assetTypeCode></managedAsset>")),
                entry("objects/61.leer.xml", utf8("<managedAsset><assetTypeCode>true</assetTypeCode></managedAsset>")),
                entry("objects/64.cv.xml", utf8("<coachView/>")),
                entry("files/61.util/util.js", utf8("function utilGlobal() {}")),
                entry("files/61.util/util.png", new byte[] {1, 2, 3}),          // binary -> skip
                entry("files/61.waise/orphan.js", utf8("function orphansOnly() {}"))));
        var util = tk.objects().stream().filter(o -> o.id().equals("61.util")).findFirst().orElseThrow();
        assertEquals("<o><name>util.js</name><assetTypeCode>J</assetTypeCode></o>", util.xml());
        assertTrue(util.xml().contains("<assetTypeCode>J</assetTypeCode>"));
        // assetTypeCode "true" (isNull form) and non-assets stay without stub
        assertTrue(tk.objects().stream().filter(o -> o.id().equals("61.leer"))
                .findFirst().orElseThrow().xml().isEmpty());
        assertTrue(tk.objects().stream().filter(o -> o.id().equals("64.cv"))
                .findFirst().orElseThrow().xml().isEmpty());
        // JS only for assets listed in the manifest; orphan without manifest entry is dropped
        assertEquals("function utilGlobal() {}", tk.jsFiles().get("61.util"));
        assertFalse(tk.jsFiles().containsKey("61.waise"));
    }

    @Test
    void toolkitPackageXmlUeberCapWirdParseError() throws Exception {
        String big = "<document><target>" + " ".repeat(TwxParser.MAX_PACKAGE_XML_BYTES) + "</target></document>";
        ByteArrayOutputStream tkBuf = new ByteArrayOutputStream();
        try (var z = new ZipOutputStream(tkBuf)) {
            z.putNextEntry(new ZipEntry("META-INF/package.xml"));
            z.write(utf8(big));
            z.closeEntry();
        }
        var r = TwxParser.parse(twx(List.of(
                entry("META-INF/package.xml", utf8(PKG)),
                entry("toolkits/bigkit.zip", tkBuf.toByteArray()))));
        assertTrue(r.errors().stream().anyMatch(e -> e.entry().contains("bigkit")
                && e.message().contains("package.xml ueber")), r.errors().toString());
    }

    // ---------- Dependencies: fallbacks without snapshot element ----------

    @Test
    void dependenciesOhneSnapshotElementMitRankFallback() throws Exception {
        String pkg = """
                <document><target>
                  <project name="D" shortName="D"/><snapshot name="1"/>
                </target>
                <dependencies>
                  <dependency id="a" snapshotId="snap-a" rank="2"><project shortName="SA"/></dependency>
                  <dependency id="b"><project shortName="SB"/></dependency>
                  <dependency><project shortName="SC"/></dependency>
                  <dependency><project shortName="SD"/></dependency>
                </dependencies></document>""";
        var deps = TwxParser.parse(twx(List.<Object[]>of(entry("META-INF/package.xml", utf8(pkg)))))
                .manifest().dependencies();
        assertEquals(4, deps.size());                          // snapshotId attribute and numeric-id fallback
        assertEquals("snap-a", deps.get(0).toolkitId());
        assertEquals(2, deps.get(0).rank());
        assertNull(deps.get(1).toolkitId());
        assertEquals(0, deps.get(3).rank());                   // rank missing -> 0
    }

    @Test
    void invalidRankBecomesNull() throws Exception {
        String pkg = """
                <document><target><project name="D" shortName="D"/></target>
                <dependencies><dependency id="a" rank="kein-number">
                  <snapshot id="s1"/></dependency></dependencies></document>""";
        var deps = TwxParser.parse(twx(List.<Object[]>of(entry("META-INF/package.xml", utf8(pkg)))))
                .manifest().dependencies();
        assertEquals(1, deps.size());
        assertEquals(0, deps.get(0).rank());
    }

    @Test
    void directoryEintraegeWerdenIgnoriert() throws Exception {
        var r = TwxParser.parse(twx(List.of(
                entry("META-INF/package.xml", utf8(PKG)),
                entry("objects/", new byte[0]),
                entry("files/", new byte[0]),
                entry("objects/1.abc.xml", utf8("<process name=\"A\"/>")))));
        assertEquals(1, r.objects().size());
    }
}

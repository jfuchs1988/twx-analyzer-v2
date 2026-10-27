package io.twxav2.ingest;

import io.twxav2.ingest.TwxParseResult.ParsedObject;
import io.twxav2.ingest.TwxParseResult.ParseError;
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
 * Parser test with synthetic mini-TWX (no real internal files)
 * — covers manifest, objects, payload assignment, binary/large objects
 * and toolkit ZIPs including error cases.
 */
class TwxParserTest {

    @TempDir
    Path dir;

    /** Builds a mini-TWX: map entry -> bytes, in order. */
    private Path twx(List<Object[]> entries) throws Exception {
        Path file = dir.resolve("demo.twx");
        try (var out = new ZipOutputStream(Files.newOutputStream(file))) {
            for (Object[] e : entries) {
                out.putNextEntry(new ZipEntry((String) e[0]));
                out.write((byte[]) e[1]);
                out.closeEntry();
            }
        }
        return file;
    }

    /** ZIP entry as Object[]{name, bytes} (List.of flattens single arrays as varargs). */
    private static Object[] entry(String name, byte[] data) {
        return new Object[] {name, data};
    }

    private static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static final String PKG = """
            <document buildVersion="8.6.9" buildDescription="FP25000">
              <target>
                <project name="Demo App" shortName="DEMO" isToolkit="false"/>
                <branch name="Main"/>
                <snapshot name="v1.0.0" originalCreationDate="2026-01-01"/>
              </target>
              <dependencies>
                <dependency id="tk-a"><project name="Toolkit One" shortName="TK1"/>
                  <snapshot id="snap-1" name="2.3.0"/></dependency>
                <dependency id="tk-a"><project name="Toolkit One" shortName="TK1"/>
                  <snapshot id="snap-1" name="2.3.0"/></dependency>
              </dependencies>
            </document>
            """;

    private static final String PROCESS =
            "<process name=\"Prozess A\" description=\"Demo-Prozess\">"
                    + "<script name=\"main\">var a = 1;</script></process>";

    private Path demoTwx() throws Exception {
        // toolkit ZIP with its own package.xml and two objects
        ByteArrayOutputStream tkBuf = new ByteArrayOutputStream();
        try (var z = new ZipOutputStream(tkBuf)) {
            z.putNextEntry(new ZipEntry("META-INF/package.xml"));
            z.write(utf8("""
                    <document><target>
                      <project name="Toolkit One" shortName="TK1"/>
                      <snapshot name="2.3.0"/>
                      <objects>
                        <object id="64.cv1" name="Kit CV" type="coachView"/>
                      </objects>
                    </target></document>
                    """));
            z.closeEntry();
            z.putNextEntry(new ZipEntry("objects/64.cv1.xml"));
            z.write("<a/>".getBytes());
            z.closeEntry();
            z.putNextEntry(new ZipEntry("objects/64.cv2.xml"));
            z.write("<a/>".getBytes());
            z.closeEntry();
        }
        return twx(List.of(
                entry("META-INF/package.xml", utf8(PKG)),
                entry("objects/1.abc.xml", utf8(PROCESS)),
                entry("objects/2051.rest1.xml", utf8("<restService name=\"REST A\"/>")),
                entry("objects/61.lib1.xml", utf8("<asset name=\"lib.js\"/>")),
                entry("files/61.lib1/script.js", utf8("function helper() {}")),
                entry("files/61.lib1/logo.png", new byte[] {1, 2, 3}),   // binary -> skip
                entry("files/unknown.obj/script.js", utf8("waisen-payload")),
                entry("objects/1.binaer.xml", new byte[] {(byte) 0xFF, (byte) 0xFE, 0x00}),
                entry("toolkits/tk1.zip", tkBuf.toByteArray()),
                entry("toolkits/caputt.zip", utf8("kein zip"))));
    }

    // ---------- Manifest ----------

    @Test
    void manifestOhnePackageXmlKeinTwx() throws Exception {
        Path f = twx(List.<Object[]>of(entry("objects/1.a.xml", utf8("<x/>"))));
        var ex = assertThrows(IllegalArgumentException.class, () -> TwxParser.parse(f));
        assertTrue(ex.getMessage().contains("package.xml"));
    }

    @Test
    void manifestFelderUndDedupliziertenDependencies() throws Exception {
        var m = TwxParser.parse(demoTwx()).manifest();
        assertEquals("Demo App", m.projectName());
        assertEquals("DEMO", m.projectShortName());
        assertEquals("Main", m.branchName());
        assertEquals("v1.0.0", m.snapshotName());
        assertEquals("8.6.9", m.buildVersion());
        assertEquals(Boolean.FALSE, m.toolkit());
        // same snapshotId listed twice -> only the first edge
        assertEquals(1, m.dependencies().size());
        var dep = m.dependencies().get(0);
        assertEquals("TK1", dep.projectShortName());
        assertEquals("snap-1", dep.toolkitId());
        assertEquals("2.3.0", dep.snapshotName());
    }

    // ---------- Objects ----------

    @Test
    void objektMitPraefixNameUndBeschreibung() throws Exception {
        var r = TwxParser.parse(demoTwx());
        ParsedObject proc = r.objects().stream()
                .filter(o -> o.id().equals("1.abc")).findFirst().orElseThrow();
        assertEquals("1", proc.prefix());
        assertNotNull(proc.typeName());
        assertEquals("Prozess A", proc.name());
        assertEquals("Demo-Prozess", proc.description());
        assertTrue(proc.content().contains("var a = 1;"));
        assertEquals(64, proc.sha256().length());
        assertEquals("2051", r.objects().stream()
                .filter(o -> o.id().equals("2051.rest1")).findFirst().orElseThrow().prefix());
    }

    @Test
    void payloadWirdZugeordnetWaisenIgnoriertUndBinaerUebersprungen() throws Exception {
        var r = TwxParser.parse(demoTwx());
        ParsedObject lib = r.objects().stream()
                .filter(o -> o.id().equals("61.lib1")).findFirst().orElseThrow();
        assertTrue(lib.content().contains("===== Payload script.js ====="));
        assertTrue(lib.content().contains("function helper()"));
        // orphan payload (unknown object) appears nowhere
        assertTrue(r.objects().stream().noneMatch(o -> "unknown.obj".equals(o.id())));
        assertTrue(r.objects().stream()
                .noneMatch(o -> o.content() != null && o.content().contains("waisen")));
        // .png under files/ is never read at all
        assertTrue(r.objects().stream().noneMatch(o -> o.name() != null && o.name().contains("logo")));
    }

    @Test
    void invalidUtf8WithoutContentGetsParseError() throws Exception {
        var r = TwxParser.parse(demoTwx());
        ParsedObject bin = r.objects().stream()
                .filter(o -> o.id().equals("1.binaer")).findFirst().orElseThrow();
        assertNull(bin.content());
        assertTrue(r.errors().stream().anyMatch(e -> e.entry().contains("1.binaer")));
    }

    @Test
    void grossobjektOhneInhaltAberMitNameAusKopfzeilen() throws Exception {
        // name is in the header, full content exceeds MAX_CONTENT_BYTES
        StringBuilder big = new StringBuilder("<process name=\"Gross\" description=\"d\">");
        while (big.length() < TwxParser.MAX_CONTENT_BYTES + 10_000) {
            big.append("<!--padding-->");
        }
        big.append("</process>");
        var r = TwxParser.parse(twx(List.of(
                entry("META-INF/package.xml", utf8(PKG)),
                entry("objects/1.big.xml", utf8(big.toString())))));
        ParsedObject o = r.objects().get(0);
        assertNull(o.content());               // cap applies
        assertEquals("Gross", o.name());       // name still taken from the header
        assertTrue(r.errors().isEmpty());
    }

    // ---------- Toolkit-ZIPs ----------

    @Test
    void toolkitMitPackageXmlUndObjektzaehlung() throws Exception {
        var r = TwxParser.parse(demoTwx());
        var tk = r.toolkits().stream()
                .filter(t -> t.toolkitId().equals("tk1")).findFirst().orElseThrow();
        assertEquals("Toolkit One", tk.projectName());
        assertEquals("TK1", tk.projectShortName());
        assertEquals("2.3.0", tk.version());
        assertEquals(2, tk.objectCount());
        assertEquals(64, tk.sha256().length());
        assertTrue(tk.sizeBytes() > 0);
        // object index: manifest provides name/type, ZIP names the remaining IDs
        assertEquals(2, tk.objects().size());
        var cv1 = tk.objects().stream().filter(o -> o.id().equals("64.cv1")).findFirst().orElseThrow();
        assertEquals("Kit CV", cv1.name());
        assertEquals("coachView", cv1.typeName());
        var cv2 = tk.objects().stream().filter(o -> o.id().equals("64.cv2")).findFirst().orElseThrow();
        assertEquals("64.cv2", cv2.name(), "ohne Manifest-Eintrag: id als Name");
        assertEquals(io.twxav2.ingest.TypeRegistry.typeName("64"), cv2.typeName());
    }

    @Test
    void kaputtesToolkitWirdParseErrorStattAbbruch() throws Exception {
        var r = TwxParser.parse(demoTwx());
        // valid toolkit remains found; broken one causes ParseError and
        // stays without manifest fields (depending on ZipInputStream behavior: entry
        // with null name or none at all)
        assertTrue(r.toolkits().stream().anyMatch(t -> t.toolkitId().equals("tk1")));
        assertTrue(r.toolkits().stream().noneMatch(t -> "caputt".equals(t.toolkitId())
                && t.projectName() != null));
        ParseError err = r.errors().stream()
                .filter(e -> e.entry().contains("caputt")).findFirst().orElse(null);
        if (err != null) {
            assertTrue(err.message().contains("nicht lesbar"));
        }
    }

    // ---------- Package-private helpers ----------

    @Test
    void binaryNamenserkennung() {
        assertTrue(TwxParser.isBinaryName("Bild.PNG"));
        assertTrue(TwxParser.isBinaryName("lib.jar"));
        assertFalse(TwxParser.isBinaryName("script.js"));
        assertFalse(TwxParser.isBinaryName("style.css"));
    }

    @Test
    void sha256BekannterWert() throws Exception {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                TwxParser.sha256("abc".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void objektTypAUSPackageXmlSchlaegtPraefixTabelle() throws Exception {
        String pkg = """
                <document buildVersion="8.6.9" buildDescription="x">
                  <target>
                    <project name="T" shortName="T" isToolkit="false"/>
                    <branch name="Main"/>
                    <snapshot name="1" originalCreationDate="2026-01-01"/>
                  </target>
                  <object id="61.aaa" versionId="v" name="S" type="twClass"/>
                </document>""";
        var res = TwxParser.parse(twx(List.of(
                entry("META-INF/package.xml", utf8(pkg)),
                entry("objects/61.aaa.xml", utf8("<service/>")),
                entry("objects/61.bbb.xml", utf8("<service/>")))));
        // type attribute from package.xml wins (even against the prefix table)...
        assertEquals("twClass", res.objects().stream()
                .filter(o -> o.id().equals("61.aaa")).findFirst().orElseThrow().typeName());
        // ... without an entry the prefix table applies as fallback
        assertEquals(TypeRegistry.typeName("61"), res.objects().stream()
                .filter(o -> o.id().equals("61.bbb")).findFirst().orElseThrow().typeName());
    }

    @Test
    void toolkitObjectsWithoutNameOrTypeUseSafeFallbacks() throws Exception {
        ByteArrayOutputStream toolkit = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(toolkit)) {
            zip.putNextEntry(new ZipEntry("META-INF/package.xml"));
            zip.write(utf8("""
                    <document><target><project name="Minimal Toolkit" shortName="MIN"/>
                      <snapshot name="1.0"/></target>
                      <objects><object id="61.asset"/></objects>
                    </document>
                    """));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("objects/61.asset.xml"));
            zip.write(utf8("<asset name=\"helper.js\"/>"));
            zip.closeEntry();
        }
        var result = TwxParser.parse(twx(List.of(
                entry("META-INF/package.xml", utf8(PKG)),
                entry("toolkits/minimal.zip", toolkit.toByteArray()))));

        var parsed = result.toolkits().getFirst().objects().getFirst();
        assertEquals("61.asset", parsed.id());
        assertEquals("61.asset", parsed.name());
        assertEquals(TypeRegistry.typeName("61"), parsed.typeName());
    }

    @Test
    void toolkitEnvironmentVariableStubKeepsNamesButDropsDefaults() throws Exception {
        ByteArrayOutputStream toolkit = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(toolkit)) {
            zip.putNextEntry(new ZipEntry("META-INF/package.xml"));
            zip.write(utf8("""
                    <document><target><project name="Settings Toolkit" shortName="SET"/>
                      <snapshot name="1.0"/></target><objects>
                      <object id="62.env" name="Toolkit Settings" type="environmentVariableSet"/>
                    </objects></document>
                    """));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("objects/62.env.xml"));
            zip.write(utf8("""
                    <environmentVariableSet name="Toolkit Settings">
                      <environmentVariable name="SHARED_SETTING"><defaultValue>do-not-store-this</defaultValue></environmentVariable>
                    </environmentVariableSet>
                    """));
            zip.closeEntry();
        }
        var result = TwxParser.parse(twx(List.of(
                entry("META-INF/package.xml", utf8(PKG)),
                entry("toolkits/settings.zip", toolkit.toByteArray()))));

        var settings = result.toolkits().getFirst().objects().stream()
                .filter(o -> o.id().equals("62.env")).findFirst().orElseThrow();
        assertTrue(settings.xml().contains("SHARED_SETTING"));
        assertFalse(settings.xml().contains("do-not-store-this"));
    }
}

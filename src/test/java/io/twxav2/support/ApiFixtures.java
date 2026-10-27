package io.twxav2.support;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import io.quarkus.test.common.http.TestHTTPResourceManager;
import io.twxav2.model.AnalysisSetting;
import io.twxav2.model.Finding;
import io.twxav2.model.ObjectExclusion;
import io.twxav2.model.Snapshot;
import io.twxav2.model.ToolkitDependency;
import io.twxav2.model.ToolkitNest;
import io.twxav2.model.ToolkitSnapshot;
import io.twxav2.model.TwxObject;
import io.twxav2.model.VariableUsage;

/**
 * Shared test infrastructure for the @QuarkusTest API tests:
 * HTTP helpers (JDK client), committing fixture setters and full wipe.
 * Fixtures are committed so the REST handlers (own transaction)
 * see them; each test class wipes before/after via {@link #wipeAll()}.
 */
@ApplicationScoped
public class ApiFixtures {

    public static String base() {
        return TestHTTPResourceManager.getUri();
    }

    // ---------- HTTP ----------

    public static HttpResponse<String> get(String path) throws Exception {
        return send(HttpRequest.newBuilder(URI.create(base() + path)).GET());
    }

    public static HttpResponse<String> delete(String path) throws Exception {
        return send(HttpRequest.newBuilder(URI.create(base() + path)).DELETE());
    }

    public static HttpResponse<String> putJson(String path, String json) throws Exception {
        return send(HttpRequest.newBuilder(URI.create(base() + path))
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8)));
    }

    public static HttpResponse<String> postJson(String path, String json) throws Exception {
        return send(HttpRequest.newBuilder(URI.create(base() + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8)));
    }

    /** multipart/form-data POST (single field "file") — without RestAssured. */
    public static HttpResponse<String> postMultipart(String path, String fileName, byte[] data) throws Exception {
        String boundary = "----twxtestboundary789";
        byte[] body = multipartBody(boundary, fileName, data);
        return send(HttpRequest.newBuilder(URI.create(base() + path))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body)));
    }

    private static byte[] multipartBody(String boundary, String fileName, byte[] data) {
        String head = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"" + fileName + "\"\r\n"
                + "Content-Type: application/zip\r\n\r\n";
        String tail = "\r\n--" + boundary + "--\r\n";
        byte[] hb = head.getBytes(StandardCharsets.UTF_8);
        byte[] tb = tail.getBytes(StandardCharsets.UTF_8);
        byte[] all = new byte[hb.length + data.length + tb.length];
        System.arraycopy(hb, 0, all, 0, hb.length);
        System.arraycopy(data, 0, all, hb.length, data.length);
        System.arraycopy(tb, 0, all, hb.length + data.length, tb.length);
        return all;
    }

    private static HttpResponse<String> send(HttpRequest.Builder b) throws Exception {
        return HttpClient.newHttpClient()
                .send(b.timeout(Duration.ofSeconds(60)).build(),
                        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    // ---------- fixtures (committed so the handlers can see them) ----------

    @Transactional
    public Snapshot snapshot(String acronym, String name, Snapshot.Status status) {
        Snapshot s = new Snapshot();
        s.filename = acronym + "-" + name + ".twx";
        s.sizeBytes = 1234;
        s.projectName = name + " App";
        s.projectAcronym = acronym;
        s.snapshotName = name;
        s.buildVersion = "8.6.9";
        s.status = status;
        s.persist();
        return s;
    }

    @Transactional
    public Finding finding(Snapshot s, String ruleId, Finding.Severity sev, Finding.Status status) {
        Finding f = new Finding();
        f.snapshot = s;
        f.ruleId = ruleId;
        f.severity = sev;
        f.category = "Skripte";
        f.score = Finding.weight(sev);
        f.objectId = s.id + ":1.abc";
        f.objectName = "Prozess A";
        f.location = "Skript main";
        f.message = "Beispielbefund " + ruleId;
        f.status = status;
        f.persist();
        return f;
    }

    /** TwxObject with XML content; twxId e.g. "1.abc" -> DB id "<snapshot>:1.abc". */
    @Transactional
    public TwxObject object(Snapshot s, String twxId, String prefix, String name, String content) {
        return object(s, twxId, prefix, null, name, content);
    }

    /** Same with subType (as TwxParser.deriveSubType fills it: service | cshs | bpd | subBpd ...). */
    @Transactional
    public TwxObject object(Snapshot s, String twxId, String prefix, String subType, String name, String content) {
        TwxObject o = new TwxObject();
        o.id = s.id + ":" + twxId;
        o.snapshot = s;
        o.prefix = prefix;
        o.typeName = io.twxav2.ingest.TypeRegistry.typeName(prefix);
        o.subType = subType;
        o.name = name;
        o.content = content;
        o.hasContent = content != null;
        o.contentBytes = content == null ? 0 : content.length();
        o.sha256 = content == null ? null : sha256Hex(content);
        o.persist();
        return o;
    }

    /** Persisted scriptLines metric of a snapshot (normally filled by the ingest). */
    @Transactional
    public void scriptLines(Snapshot s, long lines) {
        Snapshot fresh = Snapshot.findById(s.id);
        fresh.scriptLines = lines;
    }

    public static String sha256Hex(String text) {
        try {
            byte[] d = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : d) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @Transactional
    public void bumpCounters(Snapshot s) {
        Snapshot fresh = Snapshot.findById(s.id); // returned object is detached
        fresh.objectCount = (int) TwxObject.count("snapshot", s);
        fresh.findingCount = (int) Finding.count("snapshot", s);
    }

    @Transactional
    public ToolkitSnapshot toolkit(String id, String acronym, String version) {
        ToolkitSnapshot t = new ToolkitSnapshot();
        t.id = id;
        t.projectAcronym = acronym;
        t.projectName = acronym + " Toolkit";
        t.version = version;
        t.sha256 = sha256Hex(id + version);
        t.objectCount = 7;
        t.persist();
        return t;
    }

    @Transactional
    public ToolkitDependency dependency(Snapshot s, ToolkitSnapshot t) {
        ToolkitDependency d = new ToolkitDependency();
        d.snapshot = s;
        d.toolkit = t;
        d.toolkitId = t.id;
        d.declaredName = t.projectName;
        d.declaredAcronym = t.projectAcronym;
        d.declaredVersion = t.version;
        d.rank = 1;
        d.persist();
        return d;
    }

    @Transactional
    public ToolkitNest nest(ToolkitSnapshot parent, ToolkitSnapshot child) {
        ToolkitNest n = new ToolkitNest();
        n.parent = parent;
        n.childToolkitId = child.id;
        n.childAcronym = child.projectAcronym;
        n.childName = child.projectName;
        n.childVersion = child.version;
        n.rank = 1;
        n.persist();
        return n;
    }

    @Transactional
    public VariableUsage usage(Snapshot s, String objectId, String variable, boolean assign) {
        VariableUsage v = new VariableUsage();
        v.snapshot = s;
        v.objectId = objectId;
        v.objectName = "Prozess A";
        v.prefix = objectId.substring(0, objectId.indexOf('.'));
        v.scope = "local";
        v.variable = variable;
        v.assign = assign;
        v.line = 1;
        v.persist();
        return v;
    }

    // ---------- wipe ----------

    @Transactional
    public void wipeAll() {
        Finding.deleteAll();
        VariableUsage.deleteAll();
        ObjectExclusion.deleteAll();
        ToolkitDependency.deleteAll();
        ToolkitNest.deleteAll();
        ToolkitSnapshot.deleteAll();
        AnalysisSetting.deleteAll();
        TwxObject.deleteAll();
        Snapshot.deleteAll();
    }

    // ---------- synthetic TWX ZIP ----------

    /** Complete mini-TWX: manifest objects, files, embedded toolkit ZIP. */
    public static byte[] demoTwx(String snapshotName) throws Exception {
        ByteArrayOutputStream tkBuf = new ByteArrayOutputStream();
        try (var z = new ZipOutputStream(tkBuf)) {
            z.putNextEntry(new ZipEntry("META-INF/package.xml"));
            z.write("""
                    <document><target>
                      <project name="Toolkit One" shortName="TK1"/>
                      <snapshot name="2.3.0"/>
                      <objects><object id="64.cv1" name="Kit CV" type="coachView"/></objects>
                    </target></document>""".getBytes(StandardCharsets.UTF_8));
            z.closeEntry();
            z.putNextEntry(new ZipEntry("objects/64.cv1.xml"));
            z.write("<coachView name=\"Kit CV\"/>".getBytes(StandardCharsets.UTF_8));
            z.closeEntry();
        }
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(buf)) {
            z.putNextEntry(new ZipEntry("META-INF/package.xml"));
            z.write(pkg(snapshotName));
            z.closeEntry();
            z.putNextEntry(new ZipEntry("objects/1.abc.xml"));
            z.write(("<process name=\"Prozess A\" description=\"Demo\">"
                    + "<script name=\"main\">var k = tw.local.kunde;</script></process>")
                    .getBytes(StandardCharsets.UTF_8));
            z.closeEntry();
            z.putNextEntry(new ZipEntry("objects/2051.rest1.xml"));
            z.write("<restService name=\"REST A\"/>".getBytes(StandardCharsets.UTF_8));
            z.closeEntry();
            z.putNextEntry(new ZipEntry("objects/64.cv9.xml"));
            z.write("<coachView name=\"CV Neu\"><javascript>var x = 1;</javascript></coachView>"
                    .getBytes(StandardCharsets.UTF_8));
            z.closeEntry();
            z.putNextEntry(new ZipEntry("files/64.cv9/style.css"));
            z.write(".x { color: red; }".getBytes(StandardCharsets.UTF_8));
            z.closeEntry();
            z.putNextEntry(new ZipEntry("toolkits/tk1.zip"));
            z.write(tkBuf.toByteArray());
            z.closeEntry();
        }
        return buf.toByteArray();
    }

    private static byte[] pkg(String snapshotName) {
        return ("<document buildVersion=\"8.6.9\" buildDescription=\"FP25000\">"
                + "<target><project name=\"Fixture App\" shortName=\"FIXP\" isToolkit=\"false\"/>"
                + "<branch name=\"Main\"/>"
                + "<snapshot name=\"" + snapshotName + "\" originalCreationDate=\"2026-01-01\"/>"
                + "</target>"
                + "<dependencies><dependency id=\"tk-a\">"
                + "<project name=\"Toolkit One\" shortName=\"TK1\"/>"
                + "<snapshot id=\"snap-1\" name=\"2.3.0\"/></dependency></dependencies>"
                + "</document>").getBytes(StandardCharsets.UTF_8);
    }
}

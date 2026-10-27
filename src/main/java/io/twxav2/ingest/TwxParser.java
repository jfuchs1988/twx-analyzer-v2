package io.twxav2.ingest;

import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamReader;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Reads a TWX (ZIP): META-INF/package.xml, objects/&lt;typ&gt;.&lt;uuid&gt;.xml,
 * payloads under files/&lt;typ&gt;.&lt;uuid&gt;/ and embedded toolkit ZIPs under toolkits/.
 * Nothing is unpacked to disk (no Zip-Slip risk), everything stays in the stream.
 */
public final class TwxParser {

    /** Threshold beyond which object/payload content is no longer stored. */
    public static final int MAX_CONTENT_BYTES = 4_194_304;

    /** Cap for extracted toolkit ZIPs in the TWX (zip-bomb protection; real < 100 MB). */
    public static final int MAX_TOOLKIT_BYTES = 256 * 1024 * 1024;

    /** Cap for package.xml in toolkit ZIPs (metadata, real < 1 MB). */
    public static final int MAX_PACKAGE_XML_BYTES = 8 * 1024 * 1024;

    private static final XMLInputFactory XIF = XMLInputFactory.newFactory();
    static {
        XIF.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        XIF.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
    }

    private TwxParser() { }

    public static TwxParseResult parse(Path twxFile) throws Exception {
        List<TwxParseResult.ParsedObject> objects = new ArrayList<>();
        List<TwxParseResult.ParsedToolkit> toolkits = new ArrayList<>();
        List<TwxParseResult.ParseError> errors = new ArrayList<>();
        Map<String, TwxParseResult.ParsedObject> byId = new HashMap<>();
        Map<String, StringBuilder> pendingPayload = new HashMap<>();

        TwxParseResult.Manifest manifest;
        try (ZipFile zip = new ZipFile(twxFile.toFile())) {
            manifest = readManifest(zip);
            Map<String, String> pkgTypes = readObjectTypes(zip);
            Map<String, List<String>> objectTags = readObjectTags(zip);

            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry e = entries.nextElement();
                String n = e.getName();
                if (e.isDirectory()) {
                    continue;
                }
                if (n.startsWith("objects/") && n.endsWith(".xml")) {
                    String id = shortObjectId(n.substring("objects/".length(), n.length() - 4));
                    boolean[] tooLarge = new boolean[1];
                    byte[] data = readHead(zip.getInputStream(e), MAX_CONTENT_BYTES, tooLarge);
                    String sha = sha256(data);
                    // Large objects: content stays null (cap), but name/description
                    // are read from the head lines (streaming name) — otherwise they
                    // show up in the catalog as unnamed even though the name is in the head.
                    String content = tooLarge[0] ? null : asText(data, e.getName(), errors);
                    String[] nd = firstRootNameAndDescription(data, n,
                            tooLarge[0] ? new ArrayList<>() : errors);
                    String typeName = pkgTypes.getOrDefault(id,
                            TypeRegistry.typeName(TypeRegistry.prefixOf(id)));
                    var po = new TwxParseResult.ParsedObject(id, TypeRegistry.prefixOf(id),
                            typeName, nd[0], nd[1], sha, content, deriveSubType(id, data),
                            objectTags.getOrDefault(id, List.of()));
                    objects.add(po);
                    byId.put(id, po);
                } else if (n.startsWith("files/")) {
                    // files/<typ>.<uuid>/<payload>  -> assign content to its object
                    String[] parts = n.split("/");
                    if (parts.length >= 3 && isBinaryName(parts[2])) {
                        continue; // skip binary file (PNG/ZIP/...)
                    }
                    if (parts.length >= 2 && parts[1].contains(".")) {
                        String objId = shortObjectId(parts[1]);
                        byte[] data = readLimited(zip.getInputStream(e), MAX_CONTENT_BYTES);
                        String text = asText(data, n, errors);
                        if (text != null && byId.containsKey(objId)) {
                            pendingPayload.computeIfAbsent(objId, k -> new StringBuilder())
                                    .append("\n\n===== Payload ").append(parts[2]).append(" =====\n")
                                    .append(text);
                        }
                    }
                } else if (n.startsWith("toolkits/") && n.endsWith(".zip")) {
                    String toolkitId = shortObjectId(n.substring("toolkits/".length(), n.length() - 4));
                    toolkits.add(readToolkit(zip.getInputStream(e), toolkitId, errors));
                }
            }
        }

        // Attach payloads and recompute the hash of the combined content
        List<TwxParseResult.ParsedObject> merged = new ArrayList<>(objects.size());
        for (var po : objects) {
            StringBuilder extra = pendingPayload.get(po.id());
            if (extra != null && po.content() != null) {
                String joined = po.content() + extra;
                if (joined.length() > MAX_CONTENT_BYTES) {
                    joined = joined.substring(0, MAX_CONTENT_BYTES);
                }
                po = new TwxParseResult.ParsedObject(po.id(), po.prefix(), po.typeName(),
                        po.name(), po.description(), po.sha256(), joined, po.subType(), po.tags());
            }
            merged.add(po);
        }
        return new TwxParseResult(manifest, merged, toolkits, errors);
    }

    // ---------- Display sub-type ----------

    private static final Pattern P_PROCESS_TYPE = Pattern.compile("<processType>(\\d+)<");
    private static final Pattern P_PARENT_BPD = Pattern.compile("<parentBpdId[^>]*>([^<]*)<");

    /**
     * Display sub-type for the object list (changes nothing about typeName/rules):
     * prefix 1 distinguishes service (12), CSHS (10), external service (11) and
     * system processes (rest) via processType; prefix 25 distinguishes a top-level
     * BPD (no parentBpdId) from an embedded sub-process BPD.
     * Works on the head bytes so the cap (content == null) does not interfere.
     */
    static String deriveSubType(String id, byte[] head) {
        String prefix = TypeRegistry.prefixOf(id);
        String s = new String(head, StandardCharsets.UTF_8);
        if ("1".equals(prefix)) {
            Matcher m = P_PROCESS_TYPE.matcher(s);
            if (!m.find()) {
                return null;
            }
            return switch (m.group(1)) {
                case "12" -> "service";
                case "10" -> "cshs";
                case "11" -> "externalService";
                default -> "systemProcess";
            };
        }
        if ("25".equals(prefix)) {
            Matcher m = P_PARENT_BPD.matcher(s);
            return m.find() && !m.group(1).trim().isEmpty() ? "subBpd" : "bpd";
        }
        return null;
    }

    // ---------- package.xml ----------

    /**
     * &lt;object&gt; entries from package.xml: object ID (shortened) -&gt; type attribute.
     * The type attribute carries the catalog type names (process, bpd, twClass, ...); the
     * parser falls back to the prefix table for objects without an entry.
     */
    private static Map<String, String> readObjectTypes(ZipFile zip) throws Exception {
        ZipEntry e = zip.getEntry("META-INF/package.xml");
        if (e == null) {
            return Map.of();
        }
        var dbf = DocumentBuilderFactory.newDefaultInstance();
        dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        byte[] pkg = zip.getInputStream(e).readNBytes(MAX_PACKAGE_XML_BYTES + 1);
        if (pkg.length > MAX_PACKAGE_XML_BYTES) {
            return Map.of();
        }
        var doc = dbf.newDocumentBuilder().parse(new ByteArrayInputStream(pkg));
        Map<String, String> types = new HashMap<>();
        NodeList objs = doc.getElementsByTagName("object");
        for (int i = 0; i < objs.getLength(); i++) {
            Element o = (Element) objs.item(i);
            String id = o.getAttribute("id"), type = o.getAttribute("type");
            if (!id.isEmpty() && !type.isEmpty()) {
                types.put(shortObjectId(id), type);
            }
        }
        return types;
    }

    /**
     * Tags from META-INF/metadata.xml (optional; only Process Center exports have this
     * file): object ID (shortened) -&gt; tag list. Contains project objects only —
     * toolkit objects never have tags. Used for the auto-exclusions.
     */
    static Map<String, List<String>> readObjectTags(ZipFile zip) throws Exception {
        ZipEntry e = zip.getEntry("META-INF/metadata.xml");
        if (e == null) {
            return Map.of();
        }
        var dbf = DocumentBuilderFactory.newDefaultInstance();
        dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        byte[] data = zip.getInputStream(e).readNBytes(MAX_PACKAGE_XML_BYTES + 1);
        if (data.length > MAX_PACKAGE_XML_BYTES) {
            return Map.of();
        }
        var doc = dbf.newDocumentBuilder().parse(new ByteArrayInputStream(data));
        Map<String, List<String>> tags = new HashMap<>();
        NodeList objs = doc.getElementsByTagName("object");
        for (int i = 0; i < objs.getLength(); i++) {
            Element o = (Element) objs.item(i);
            String id = o.getAttribute("id");
            if (id.isEmpty()) {
                continue;
            }
            List<String> list = new ArrayList<>();
            // tag children belong to their object block: via nested search
            NodeList inner = o.getElementsByTagName("tag");
            for (int t = 0; t < inner.getLength(); t++) {
                String v = inner.item(t).getTextContent();
                if (v != null && !v.isBlank()) {
                    list.add(v.trim());
                }
            }
            if (!list.isEmpty()) {
                tags.put(shortObjectId(id), List.copyOf(list));
            }
        }
        return tags;
    }

    private static TwxParseResult.Manifest readManifest(ZipFile zip) throws Exception {
        ZipEntry e = zip.getEntry("META-INF/package.xml");
        if (e == null) {
            throw new IllegalArgumentException("Kein META-INF/package.xml - keine gueltige TWX-Datei");
        }
        var dbf = DocumentBuilderFactory.newDefaultInstance();
        dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        // Same cap as in the toolkit branch: DOM inflates XML heavily (heap)
        byte[] pkg = zip.getInputStream(e).readNBytes(MAX_PACKAGE_XML_BYTES + 1);
        if (pkg.length > MAX_PACKAGE_XML_BYTES) {
            throw new IllegalArgumentException("package.xml ueber " + (MAX_PACKAGE_XML_BYTES / 1024) + " kB");
        }
        var doc = dbf.newDocumentBuilder().parse(new ByteArrayInputStream(pkg));

        Element project = first(doc.getDocumentElement(), "target", "project");
        Element branch = first(doc.getDocumentElement(), "target", "branch");
        Element snapshot = first(doc.getDocumentElement(), "target", "snapshot");
        Element root = doc.getDocumentElement();

        List<TwxParseResult.Dependency> deps = parseDependencies(doc);
        return new TwxParseResult.Manifest(
                project != null ? attr(project, "name") : null,
                project != null ? attr(project, "shortName") : null,
                project != null ? attr(project, "description") : null,
                branch != null ? attr(branch, "name") : null,
                snapshot != null ? attr(snapshot, "name") : null,
                snapshot != null ? attr(snapshot, "description") : null,
                snapshot != null ? attr(snapshot, "originalCreationDate") : null,
                attr(root, "buildVersion"),
                attr(root, "buildDescription"),
                project != null ? "true".equalsIgnoreCase(attr(project, "isToolkit")) : null,
                deps);
    }

    // ---------- Toolkit-ZIPs ----------

    /** All &lt;dependencies&gt of package.xml (manifest of the TWX as well as toolkit ZIPs). */
    static List<TwxParseResult.Dependency> parseDependencies(org.w3c.dom.Document doc) {
        List<TwxParseResult.Dependency> deps = new ArrayList<>();
        Element depsEl = first(doc.getDocumentElement(), "dependencies");
        if (depsEl == null) {
            return deps;
        }
        java.util.Set<String> seen = new java.util.HashSet<>();
        NodeList list = depsEl.getElementsByTagName("dependency");
        for (int i = 0; i < list.getLength(); i++) {
            Element d = (Element) list.item(i);
            Element dp = firstChild(d, "project");
            Element ds = firstChild(d, "snapshot");
            String snapId = ds != null ? attr(ds, "id") : attr(d, "snapshotId");
            // package.xml sometimes names the same snapshot repeatedly -> only the first edge counts
            if (!seen.add(snapId != null ? snapId : attr(d, "id") + "/v" + i)) {
                continue;
            }
            deps.add(new TwxParseResult.Dependency(
                    attr(d, "id"),
                    dp != null ? attr(dp, "name") : null,
                    dp != null ? attr(dp, "shortName") : null,
                    snapId,
                    ds != null ? attr(ds, "name") : null,
                    parseInt(attr(d, "rank"))));
        }
        return deps;
    }

    private static TwxParseResult.ParsedToolkit readToolkit(InputStream in, String toolkitId,
                                                            List<TwxParseResult.ParseError> errors) throws Exception {
        // Size cap against zip bombs: extracted entries can be much larger
        // than the TWX file itself; readAllBytes() without a cap = OOM risk (heap x concurrency)
        byte[] zipBytes = in.readNBytes(MAX_TOOLKIT_BYTES + 1);
        if (zipBytes.length > MAX_TOOLKIT_BYTES) {
            throw new IllegalArgumentException("Toolkit " + toolkitId + ": entpackt ueber "
                    + (MAX_TOOLKIT_BYTES / (1024 * 1024)) + " MB — Datei abgelehnen");
        }
        String sha = sha256(zipBytes);
        String projectName = null, shortName = null, version = null;
        int objectCount = 0;
        List<TwxParseResult.Dependency> nested = List.of();
        // Toolkit object index (id -> name/type from its package.xml): needed so
        // reference rules can resolve target objects in toolkits (R-DEP-006/R-SVC-004/R-BPD-003).
        Map<String, String[]> manifestObjects = new java.util.LinkedHashMap<>();
        java.util.Set<String> entryIds = new java.util.LinkedHashSet<>();
        // Server-file contents (Managed Assets) from files/<id>/... — needed so R-JS-025
        // knows globals from toolkit server files (utils.js -> global export); likewise the small
        // asset object XML (name/assetTypeCode) as stub replacement for the reference solution.
        Map<String, StringBuilder> assetJs = new java.util.LinkedHashMap<>();
        Map<String, String> assetXml = new java.util.LinkedHashMap<>();
        Map<String, String> environmentVariableXml = new java.util.LinkedHashMap<>();
        try (var zis = new java.util.zip.ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                if (e.isDirectory()) {
                    continue;
                }
                if (e.getName().equals("META-INF/package.xml")) {
                    var dbf = DocumentBuilderFactory.newDefaultInstance();
                    dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
                    // The DOM parser closes the stream passed to it -> otherwise the
                    // ZipInputStream dies and the next getNextEntry() throws "Stream closed".
                    byte[] pkg = zis.readNBytes(MAX_PACKAGE_XML_BYTES + 1);
                    if (pkg.length > MAX_PACKAGE_XML_BYTES) {
                        throw new IllegalArgumentException(
                                "package.xml ueber " + (MAX_PACKAGE_XML_BYTES / 1024) + " kB");
                    }
                    var doc = dbf.newDocumentBuilder().parse(new ByteArrayInputStream(pkg));
                    Element p = first(doc.getDocumentElement(), "target", "project");
                    Element s = first(doc.getDocumentElement(), "target", "snapshot");
                    projectName = p != null ? attr(p, "name") : null;
                    shortName = p != null ? attr(p, "shortName") : null;
                    version = s != null ? attr(s, "name") : null;
                    nested = parseDependencies(doc);
                    NodeList objs = doc.getElementsByTagName("object");
                    for (int i = 0; i < objs.getLength(); i++) {
                        Element o = (Element) objs.item(i);
                        String id = shortObjectId(attr(o, "id"));
                        if (!id.isEmpty()) {
                            manifestObjects.put(id, new String[] {attr(o, "name"), attr(o, "type")});
                        }
                    }
                } else if (e.getName().startsWith("objects/")) {
                    objectCount++;
                    String id = shortObjectId(e.getName().substring("objects/".length()));
                    if (id.endsWith(".xml")) {
                        id = id.substring(0, id.length() - 4);
                    }
                    if (id.matches("\\d{1,3}\\.[0-9A-Za-z-]+")) {
                        entryIds.add(id);
                    }
                    // Read asset objects too: we need name/assetTypeCode as
                    // stub XML so jsFiles targets are recognizable as server files (R-JS-025).
                    if (e.getName().endsWith(".xml") && isAssetLike(id, manifestObjects)) {
                        String t = asText(zis.readNBytes(MAX_CONTENT_BYTES), e.getName(), errors);
                        if (t != null) {
                            assetXml.put(id, t);
                        }
                    } else if (e.getName().endsWith(".xml") && isEnvironmentVariableSetLike(id, manifestObjects)) {
                        String t = asText(zis.readNBytes(MAX_CONTENT_BYTES), e.getName(), errors);
                        if (t != null) {
                            environmentVariableXml.put(id, environmentVariableNamesStub(t));
                        }
                    }
                } else if (e.getName().startsWith("files/")) {
                    // JS payloads of the Managed Assets (files/<assetId>/<file>) — server
                    // files define globals for server-side scripts (R-JS-025/R-UI-001).
                    String[] parts = e.getName().split("/");
                    if (parts.length >= 3 && !isBinaryName(parts[2]) && parts[1].contains(".")
                            && isAssetLike(shortObjectId(parts[1]), manifestObjects)) {
                        String t = asText(zis.readNBytes(MAX_CONTENT_BYTES), e.getName(), errors);
                        if (t != null && !t.isBlank()) {
                            assetJs.computeIfAbsent(shortObjectId(parts[1]), k -> new StringBuilder()).append(t);
                        }
                    }
                }
            }
        } catch (Exception ex) {
            errors.add(new TwxParseResult.ParseError("toolkits/" + toolkitId + ".zip",
                    "Toolkit-ZIP nicht lesbar: " + ex.getMessage()));
        }
        List<TwxParseResult.ToolkitObject> objects = new ArrayList<>();
        java.util.Set<String> ids = new java.util.LinkedHashSet<>(entryIds);
        ids.addAll(manifestObjects.keySet());
        for (String id : ids) {
            String[] mt = manifestObjects.get(id);
            String name = mt != null && mt[0] != null && !mt[0].isEmpty() ? mt[0] : id;
            String type = mt != null && mt[1] != null && !mt[1].isEmpty()
                    ? mt[1] : TypeRegistry.typeName(TypeRegistry.prefixOf(id));
            String raw = assetXml.get(id);
            String xml = raw != null ? assetStubXml(raw)
                    : environmentVariableXml.getOrDefault(id, "");
            objects.add(new TwxParseResult.ToolkitObject(id, name, type, xml));
        }
        Map<String, String> jsFiles = new java.util.LinkedHashMap<>();
        for (var en : assetJs.entrySet()) {
            if (ids.contains(en.getKey())) {
                jsFiles.put(en.getKey(), en.getValue().toString());
            }
        }
        return new TwxParseResult.ParsedToolkit(toolkitId, projectName, shortName, version, sha,
                zipBytes.length, objectCount, nested, objects, jsFiles);
    }

    /** Manifest type or prefix type mentions "asset" (managedAsset 61.*). */
    private static boolean isAssetLike(String id, Map<String, String[]> manifestObjects) {
        String[] mt = manifestObjects.get(id);
        String type = mt != null && mt[1] != null && !mt[1].isEmpty()
                ? mt[1] : TypeRegistry.typeName(TypeRegistry.prefixOf(id));
        return type != null && type.toLowerCase(java.util.Locale.ROOT).contains("asset");
    }

    private static boolean isEnvironmentVariableSetLike(String id, Map<String, String[]> manifestObjects) {
        String[] mt = manifestObjects.get(id);
        String type = mt != null && mt[1] != null && !mt[1].isEmpty()
                ? mt[1] : TypeRegistry.typeName(TypeRegistry.prefixOf(id));
        return type != null && type.toLowerCase(java.util.Locale.ROOT).contains("environmentvariableset");
    }

    /** Keep only environment-variable names from toolkit object XML; defaults may contain secrets. */
    private static String environmentVariableNamesStub(String raw) {
        Matcher names = Pattern.compile("<environmentVariable\\b[^>]*\\bname=\"([^\"]*)\"").matcher(raw);
        StringBuilder stub = new StringBuilder("<environmentVariableSet>");
        while (names.find()) {
            String name = names.group(1).replace("&", "&amp;").replace("\"", "&quot;")
                    .replace("<", "&lt;").replace(">", "&gt;");
            stub.append("<environmentVariable name=\"").append(name).append("\"/>");
        }
        return stub.append("</environmentVariableSet>").toString();
    }

    private static final Pattern ASSET_TYPE = Pattern.compile("<assetTypeCode[^>]*>([^<]*)<");
    private static final Pattern NAME_ATTR = Pattern.compile("<[^>]*\\bname=\"([^\"]*)\"[^>]*>");

    /** Minimal stub XML (name/assetTypeCode) from the toolkit asset — for the R-JS-025 global resolution. */
    private static String assetStubXml(String raw) {
        Matcher at = ASSET_TYPE.matcher(raw);
        String atc = at.find() ? at.group(1).trim() : "";
        if (atc.isEmpty() || "true".equalsIgnoreCase(atc)) {
            return ""; // no assetTypeCode or isNull form
        }
        Matcher nm = NAME_ATTR.matcher(raw);
        String name = nm.find() ? nm.group(1) : "";
        return "<o><name>" + escapeXmlText(name) + "</name><assetTypeCode>"
                + escapeXmlText(atc) + "</assetTypeCode></o>";
    }

    private static String escapeXmlText(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    // ---------- Helpers ----------

    /** name and description attributes of the first element with a name attribute. */
    private static String[] firstRootNameAndDescription(byte[] data, String entryName,
                                                        List<TwxParseResult.ParseError> errors) {
        XMLStreamReader r = null; // XMLStreamReader is not AutoCloseable
        try {
            r = XIF.createXMLStreamReader(new ByteArrayInputStream(data));
            while (r.hasNext()) {
                if (r.next() == XMLStreamConstants.START_ELEMENT) {
                    String name = r.getAttributeValue(null, "name");
                    if (name != null) {
                        return new String[]{ name, r.getAttributeValue(null, "description") };
                    }
                }
            }
        } catch (Exception ex) {
            errors.add(new TwxParseResult.ParseError(entryName,
                    "XML nicht lesbar: " + truncate(ex.getMessage(), 200)));
        } finally {
            if (r != null) {
                try {
                    r.close();
                } catch (Exception ignored) {
                    // close errors are not relevant here
                }
            }
        }
        return new String[]{ null, null };
    }

    private static String asText(byte[] data, String entryName, List<TwxParseResult.ParseError> errors) {
        if (data.length >= MAX_CONTENT_BYTES) {
            return null; // too large -> stored without content
        }
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .decode(java.nio.ByteBuffer.wrap(data)).toString();
        } catch (Exception ex) {
            return null; // not valid UTF-8 -> binary
        }
    }

    static boolean isBinaryName(String n) {
        String l = n.toLowerCase(Locale.ROOT);
        return l.endsWith(".png") || l.endsWith(".jpg") || l.endsWith(".jpeg") || l.endsWith(".gif")
                || l.endsWith(".zip") || l.endsWith(".jar") || l.endsWith(".ico") || l.endsWith(".pdf")
                || l.endsWith(".ttf") || l.endsWith(".woff") || l.endsWith(".woff2") || l.endsWith(".eot");
    }

    /**
     * Read the head of the file: at most limit bytes, but the real bytes
     * (no marker array) — tooLarge[0] indicates that more data followed.
     * For name/description from the head lines of large objects.
     */
    private static byte[] readHead(InputStream in, int limit, boolean[] tooLarge)
            throws java.io.IOException {
        var bos = new java.io.ByteArrayOutputStream(Math.min(limit, 64 * 1024));
        byte[] buf = new byte[16 * 1024];
        int read = 0, n;
        while (read < limit && (n = in.read(buf, 0, Math.min(buf.length, limit - read))) > 0) {
            bos.write(buf, 0, n);
            read += n;
        }
        tooLarge[0] = read >= limit && in.read() != -1;
        return bos.toByteArray();
    }

    private static byte[] readLimited(InputStream in, int limit) throws java.io.IOException {
        var bos = new java.io.ByteArrayOutputStream(Math.min(limit, 64 * 1024));
        byte[] buf = new byte[16 * 1024];
        int read = 0, n;
        while ((n = in.read(buf)) > 0) {
            if (read + n > limit) {
                bos.write(buf, 0, limit - read);
                return new byte[limit + 1]; // "too large" marker past the limit
            }
            bos.write(buf, 0, n);
            read += n;
        }
        return bos.toByteArray();
    }

    static String sha256(byte[] data) throws Exception {
        var md = MessageDigest.getInstance("SHA-256");
        return HexFormat.of().formatHex(md.digest(data));
    }

    /**
     * Object identifier from the ZIP entry name (objects and toolkit ZIPs).
     * BPM exports with use_enhanced filenames produce very long names
     * (&gt; column width 64, incl. snapshot prefix); long stems are
     * reduced collision-safely to prefix + short SHA-256 digest and stay
     * stable so payload assignment (files/&lt;obj&gt;/) and dedup keep working.
     */
    private static String shortObjectId(String stem) {
        if (stem.length() <= 58) {
            return stem;
        }
        int dot = stem.indexOf('.');
        String prefix = dot > 0 ? stem.substring(0, dot) : "obj";
        try {
            return prefix + "." + sha256(stem.getBytes(StandardCharsets.UTF_8)).substring(0, 40);
        } catch (Exception e) {
            return stem.substring(0, 58);
        }
    }

    private static Element first(Element root, String... path) {
        Element cur = root;
        for (String p : path) {
            cur = firstChild(cur, p);
            if (cur == null) {
                return null;
            }
        }
        return cur;
    }

    private static Element firstChild(Element parent, String tag) {
        NodeList list = parent.getElementsByTagNameNS("*", tag);
        for (int i = 0; i < list.getLength(); i++) {
            if (list.item(i).getParentNode() == parent) {
                return (Element) list.item(i);
            }
        }
        return parent.getElementsByTagName(tag).getLength() > 0
                ? (Element) parent.getElementsByTagName(tag).item(0) : null;
    }

    private static String attr(Element e, String name) {
        String v = e.getAttribute(name);
        return v.isEmpty() ? null : v;
    }

    private static int parseInt(String s) {
        try {
            return s == null ? 0 : Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    public static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max - 3) + "...";
    }
}

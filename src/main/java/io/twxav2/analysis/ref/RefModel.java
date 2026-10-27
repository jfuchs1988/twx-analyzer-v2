// Derived from Dosvak LLC twx-code-analyzer (Apache-2.0); modified for TWX Analyzer v2. See NOTICE.
package io.twxav2.analysis.ref;

import io.twxav2.analysis.ref.model.TwxModel;
import io.twxav2.analysis.ref.model.TwxObject;
import io.twxav2.analysis.ref.model.TwxPackage;
import io.twxav2.analysis.ref.util.Xml;
import io.twxav2.ingest.TwxParseResult;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Builds the rule catalog's reference model from the house parse result
 * ({@link TwxParseResult}) — without the ZIP layer
 * (TwxParser already took care of that).
 *
 * <p>Deliberate model deviations:
 * <ul>
 *   <li>Toolkit objects are embedded as stubs (id/name/type from the toolkit
 *       manifest; Managed Assets additionally with minimal name/assetTypeCode XML),
 *       toolkit JS payloads live in {@code jsFiles} — references to toolkit
 *       objects resolve, server file globals are known (R-JS-025), but
 *       toolkit-internal checks see empty content.</li>
 *   <li>{@code Dependency.system} is derived from the acronym (SYS*, TWSYS,
 *       BPMUI) because package.xml isSystem is not passed through.</li>
 * </ul>
 */
public final class RefModel {

    /** GUID-like names of embedded sub-BPDs. */
    private static final Pattern GUID_NAME =
            Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

    private RefModel() { }

    public static TwxModel from(TwxParseResult parsed) {
        TwxModel m = new TwxModel();
        m.app = appPackage(parsed);
        for (TwxParseResult.ParsedToolkit tk : parsed.toolkits()) {
            TwxPackage tp = new TwxPackage();
            tp.id = nz(tk.toolkitId());
            tp.snapshotId = nz(tk.toolkitId());
            tp.name = nz(tk.projectName());
            tp.acronym = nz(tk.projectShortName());
            tp.snapshotName = nz(tk.version());
            tp.toolkit = true;
            tp.zipSize = tk.sizeBytes();
            // object stubs from the toolkit manifest (id/name/type; Managed Assets with
            // minimal name/assetTypeCode XML): makes references to toolkit objects
            // resolvable (R-DEP-006/R-SVC-004/R-BPD-003) and toolkit server files recognizable as such
            // (R-JS-025). Rules still do not iterate toolkit objects (includeToolkits=false).
            for (TwxParseResult.ToolkitObject mo : tk.objects()) {
                tp.objects.put(mo.id(), new TwxObject(mo.id(), nz(mo.name()), nz(mo.typeName()),
                        "", nz(mo.xml()), tp));
            }
            tp.jsFiles.putAll(tk.jsFiles());
            for (TwxParseResult.Dependency d : tk.nested()) {
                tp.dependencies.add(dependency(d));
            }
            m.toolkits.put(tp.snapshotId, tp);
        }
        for (TwxParseResult.ParseError err : parsed.errors()) {
            m.diagnostic("parse-error", nz(err.entry()) + ": " + nz(err.message()));
        }
        nameEmbeddedProcesses(m.app);
        return m;
    }

    private static TwxPackage appPackage(TwxParseResult parsed) {
        TwxPackage p = new TwxPackage();
        TwxParseResult.Manifest mf = parsed.manifest();
        p.name = nz(mf.projectName());
        p.acronym = nz(mf.projectShortName());
        p.description = nz(mf.projectDescription());
        p.branchName = nz(mf.branchName());
        p.snapshotName = nz(mf.snapshotName());
        p.snapshotDate = nz(mf.snapshotDate());
        p.buildVersion = nz(mf.buildVersion());
        p.toolkit = Boolean.TRUE.equals(mf.toolkit());
        for (TwxParseResult.Dependency d : mf.dependencies()) {
            p.dependencies.add(dependency(d));
        }
        for (TwxParseResult.ParsedObject o : parsed.objects()) {
            p.objects.put(o.id(), new TwxObject(o.id(), nz(o.name()), nz(o.typeName()),
                    "", o.content() == null ? "" : o.content(), p));
            // Managed Assets: their JS payload (files/<id>/..., appended as text by
            // the TwxParser) defines server file globals for R-JS-025/R-UI-001.
            if (o.content() != null && o.content().contains("<assetTypeCode")) {
                String js = payloadOf(o.content());
                if (!js.isBlank()) {
                    p.jsFiles.put(o.id(), js);
                }
            }
        }
        return p;
    }

    /** Text behind the TwxParser's "===== Payload <file> =====" markers (without marker lines). */
    private static String payloadOf(String content) {
        int i = content.indexOf("\n\n===== Payload ");
        if (i < 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (String seg : content.substring(i).split("\n\n===== Payload ")) {
            int nl = seg.indexOf('\n');
            if (nl >= 0) {
                sb.append(seg, nl + 1, seg.length());
            }
        }
        return sb.toString();
    }

    private static TwxPackage.Dependency dependency(TwxParseResult.Dependency d) {
        TwxPackage.Dependency r = new TwxPackage.Dependency();
        r.projectId = nz(d.projectId());
        r.name = nz(d.projectName());
        r.acronym = nz(d.projectShortName());
        r.snapshotId = nz(d.toolkitId());
        r.snapshotName = nz(d.snapshotName());
        String a = r.acronym;
        r.system = a.startsWith("SYS") || a.equals("TWSYS") || a.equals("BPMUI") || a.equals("SYSC");
        return r;
    }

    /** Embedded sub-BPDs carry their GUID as name — name them after the activity. */
    private static void nameEmbeddedProcesses(TwxPackage p) {
        List<TwxObject> bpds = p.byType("bpd");
        for (TwxObject o : bpds) {
            if (!GUID_NAME.matcher(o.name).matches()) {
                continue;
            }
            String ref = "<embeddedProcessId>" + o.id + "</embeddedProcessId>";
            for (TwxObject b : bpds) {
                int i = b.xml.indexOf(ref);
                if (b == o || i < 0) {
                    continue;
                }
                int fo = b.xml.lastIndexOf("<flowObject", i);
                String activity = fo >= 0 ? Xml.text(b.xml.substring(fo, i), "name") : "";
                o.name = (activity.isEmpty() ? "embedded process" : activity) + " (in " + b.name + ")";
                break;
            }
        }
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}

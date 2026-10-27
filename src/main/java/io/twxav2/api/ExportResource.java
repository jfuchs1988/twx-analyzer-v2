package io.twxav2.api;

import io.twxav2.api.dto.FindingDto;
import io.twxav2.api.dto.SnapshotDto;
import io.twxav2.model.Finding;
import io.twxav2.model.Snapshot;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.resteasy.reactive.RestQuery;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Finding export (B9) for CI and further processing: JSON or CSV (semicolon,
 * UTF-8 with BOM - opens directly in Excel). Status filter: effective = without
 * accepted findings (like health/gate, default), open = only open,
 * all = everything. The optional API token (twxav2.api.token) protects this
 * path via ApiTokenFilter; the UI endpoints remain without a token requirement.
 */
@Path("/api/export")
public class ExportResource {

    @GET
    @Path("{id}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response json(@PathParam("id") Long snapshotId,
                         @DefaultValue("effective") @RestQuery String status) {
        Snapshot s = Snapshot.findById(snapshotId);
        if (s == null) {
            return Response.status(404).entity(java.util.Map.of("error", "Snapshot nicht gefunden")).build();
        }
        List<FindingDto> findings = query(snapshotId, status).stream().map(FindingDto::of).toList();
        return Response.ok(new ExportPayload(
                s.id, s.projectAcronym, s.projectName, s.snapshotName,
                findings.size(), findings)).build();
    }

    public record ExportPayload(Long snapshotId, String projectAcronym, String projectName,
                                String snapshotName, int count, List<FindingDto> findings) { }

    @GET
    @Path("{id}/csv")
    @Produces("text/csv")
    public Response csv(@PathParam("id") Long snapshotId,
                        @DefaultValue("effective") @RestQuery String status) {
        Snapshot s = Snapshot.findById(snapshotId);
        if (s == null) {
            return Response.status(404).entity(java.util.Map.of("error", "Snapshot nicht gefunden")).build();
        }
        StringBuilder sb = new StringBuilder("\uFEFF"); // BOM: Excel recognizes UTF-8
        sb.append(String.join(";",
                "Rule", "Severity", "Category", "Score", "Needs Review",
                "Object ID", "Object", "Location", "Line", "Message",
                "Status", "Comment")).append("\r\n");
        for (Finding f : query(snapshotId, status)) {
            FindingDto d = FindingDto.of(f);
            sb.append(csvCell(d.ruleId())).append(';')
              .append(csvCell(d.severity())).append(';')
              .append(csvCell(d.category())).append(';')
              .append(d.score() == null ? "" : d.score()).append(';')
              .append(d.needsReview() ? "yes" : "").append(';')
              .append(csvCell(d.objectId())).append(';')
              .append(csvCell(d.objectName())).append(';')
              .append(csvCell(d.location())).append(';')
              .append(d.line() == null ? "" : d.line()).append(';')
              .append(csvCell(d.message())).append(';')
              .append(csvCell(d.status())).append(';')
              .append(csvCell(d.statusComment())).append("\r\n");
        }
        String name = ("twx-checkup-" + nvl(s.projectAcronym, s.projectName, s.filename)
                + "-" + nvl(s.snapshotName, String.valueOf(s.id)) + ".csv")
                .replaceAll("[\\\\p{Cntrl}\\\\/:*?\"<>|]", "_");
        return Response.ok(sb.toString().getBytes(StandardCharsets.UTF_8))
                .header("Content-Disposition", "attachment; filename=\"" + name + "\"")
                .build();
    }

    /**
     * HTML report (B9): self-contained, print-optimized — the browser turns it
     * into a PDF report via "Save as PDF" (house decision: no
     * own PDF renderer).
     */
    @GET
    @Path("{id}/html")
    @Produces(MediaType.TEXT_HTML)
    public Response html(@PathParam("id") Long snapshotId,
                         @DefaultValue("effective") @RestQuery String status) {
        Snapshot s = Snapshot.findById(snapshotId);
        if (s == null) {
            return Response.status(404).entity(java.util.Map.of("error", "Snapshot nicht gefunden")).build();
        }
        List<FindingDto> findings = query(snapshotId, status).stream().map(FindingDto::of).toList();
        int totalScore = findings.stream().mapToInt(f -> f.score() == null ? 0 : f.score()).sum();
        Integer health = findings.isEmpty() && s.objectCount == 0
                ? null : SnapshotDto.healthScore(totalScore, Math.max(1, s.objectCount));
        long critical = findings.stream().filter(f -> "CRITICAL".equals(f.severity())).count();
        long major = findings.stream().filter(f -> "MAJOR".equals(f.severity())).count();
        long minor = findings.stream().filter(f -> "MINOR".equals(f.severity())).count();
        long info = findings.size() - critical - major - minor;

        StringBuilder sb = new StringBuilder(findings.size() * 160 + 4096);
         sb.append("<!DOCTYPE html><html lang=\"en\"><head><meta charset=\"utf-8\">")
          .append("<title>twx-checkup — ").append(esc(nvl(s.projectAcronym, s.projectName, s.filename)))
          .append(" ").append(esc(nvl(s.snapshotName, ""))).append("</title><style>")
          .append("body{font:13px/1.45 'Segoe UI',system-ui,sans-serif;color:#1a1a2e;margin:24px}")
          .append("h1{font-size:20px;margin:0 0 2px}h2{font-size:15px;margin:22px 0 6px}")
          .append(".sub{color:#666;font-size:12px;margin-bottom:14px}")
          .append(".cards{display:flex;gap:10px;flex-wrap:wrap;margin:10px 0 4px}")
          .append(".card{border:1px solid #d8d8e0;border-radius:8px;padding:8px 14px;min-width:110px}")
          .append(".card b{font-size:18px;display:block}.card span{font-size:11px;color:#666;text-transform:uppercase}")
          .append(".health{color:#fff}.health.ok{background:#2b8a3e}.health.mid{background:#e67700}.health.bad{background:#c92a2a}")
          .append("table{border-collapse:collapse;width:100%;font-size:12px}")
          .append("th,td{border:1px solid #d8d8e0;padding:4px 7px;text-align:left;vertical-align:top}")
          .append("th{background:#f1f0f5;text-transform:uppercase;font-size:10px;letter-spacing:.03em}")
          .append("tr{page-break-inside:avoid}")
          .append(".sev{font-weight:700;white-space:nowrap}.CRITICAL{color:#c92a2a}.MAJOR{color:#e67700}")
          .append(".MINOR{color:#1c7ed6}.INFO{color:#868e96}")
          .append(".mono{font-family:Consolas,monospace;font-size:11px;word-break:break-all}")
          .append("@media print{body{margin:8mm}.card{border-color:#999}a{color:inherit;text-decoration:none}}")
          .append("</style></head><body>");
        sb.append("<h1>twx-checkup — ").append(esc(nvl(s.projectAcronym, s.projectName, s.filename)))
          .append(" ").append(esc(nvl(s.snapshotName, String.valueOf(s.id)))).append("</h1>")
          .append("<div class=\"sub\">Snapshot #").append(s.id)
          .append(" · ").append(s.objectCount).append(" objects · exported ")
          .append(java.time.OffsetDateTime.now().toLocalDate())
          .append(" · status filter: ").append(esc(status)).append("</div>");
        sb.append("<div class=\"cards\">")
          .append("<div class=\"card\"><b>").append(findings.size()).append("</b><span>Findings</span></div>")
          .append("<div class=\"card\"><b class=\"CRITICAL\">").append(critical).append("</b><span>Critical</span></div>")
          .append("<div class=\"card\"><b class=\"MAJOR\">").append(major).append("</b><span>Major</span></div>")
          .append("<div class=\"card\"><b class=\"MINOR\">").append(minor).append("</b><span>Minor</span></div>")
          .append("<div class=\"card\"><b class=\"INFO\">").append(info).append("</b><span>Info</span></div>");
        if (health != null) {
            String cls = health >= 70 ? "ok" : health >= 40 ? "mid" : "bad";
            sb.append("<div class=\"card health ").append(cls).append("\"><b>").append(health)
              .append("</b><span>Health</span></div>");
        }
        sb.append("</div>");

        sb.append("<h2>Findings by rule</h2><table><tr><th>Rule</th><th>Severity</th><th>Findings</th><th>Score</th></tr>");
        java.util.Map<String, long[]> perRule = new java.util.LinkedHashMap<>();
        java.util.Map<String, String> ruleSev = new java.util.LinkedHashMap<>();
        for (FindingDto f : findings) {
            long[] acc = perRule.computeIfAbsent(f.ruleId(), k -> new long[2]);
            acc[0]++;
            acc[1] += f.score() == null ? 0 : f.score();
            ruleSev.putIfAbsent(f.ruleId(), f.severity());
        }
        perRule.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue()[1], a.getValue()[1]))
                .forEach(e -> sb.append("<tr><td class=\"mono\">").append(esc(e.getKey()))
                        .append("</td><td class=\"sev ").append(esc(ruleSev.get(e.getKey()))).append("\">")
                        .append(esc(ruleSev.get(e.getKey()))).append("</td><td>").append(e.getValue()[0])
                        .append("</td><td>").append(e.getValue()[1]).append("</td></tr>"));
        sb.append("</table>");

        sb.append("<h2>Individual findings</h2><table><tr><th>Rule</th><th>Severity</th><th>Score</th>")
          .append("<th>Object</th><th>Location</th><th>Message</th><th>Status</th></tr>");
        for (FindingDto f : findings) {
            sb.append("<tr>")
              .append("<td class=\"mono\">").append(esc(f.ruleId())).append("</td>")
              .append("<td class=\"sev ").append(esc(f.severity())).append("\">").append(esc(f.severity())).append("</td>")
              .append("<td>").append(f.score() == null ? "" : f.score()).append("</td>")
              .append("<td>").append(esc(nvl(f.objectName(), f.objectId(), "—"))).append("</td>")
              .append("<td>").append(esc(f.location() == null ? "" : f.location())
                       + (f.line() == null ? "" : " · line " + f.line())).append("</td>")
              .append("<td>").append(esc(f.message())).append("</td>")
              .append("<td>").append(esc(f.status() == null || "OPEN".equals(f.status()) ? "" : f.status())).append("</td>")
              .append("</tr>");
        }
        sb.append("</table></body></html>");

        String name = ("twx-checkup-" + nvl(s.projectAcronym, s.projectName, s.filename)
                + "-" + nvl(s.snapshotName, String.valueOf(s.id)) + ".html")
                .replaceAll("[\\\\p{Cntrl}\\\\/:*?\"<>|]", "_");
        return Response.ok(sb.toString().getBytes(StandardCharsets.UTF_8))
                .header("Content-Disposition", "inline; filename=\"" + name + "\"")
                .build();
    }

    private static String esc(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static List<Finding> query(Long snapshotId, String status) {
        String q = "snapshot.id = ?1";
        if (status == null || status.isBlank() || "effective".equalsIgnoreCase(status)) {
            q += " and (status is null or status <> 'ACCEPTED')";
        } else if ("open".equalsIgnoreCase(status)) {
            q += " and (status is null or status = 'OPEN')";
        } else if (!"all".equalsIgnoreCase(status)) {
            // do NOT silently interpret an unknown value as effective (otherwise CI
            // would quietly deliver falsified numbers for status=opne)
            throw new jakarta.ws.rs.WebApplicationException(Response.status(400)
                    .entity(java.util.Map.of("error",
                            "Unbekannter status: " + status + " (erlaubt: effective, open, all)"))
                    .build());
        }
        return Finding.<Finding>list(q + " order by score desc, ruleId, objectName", snapshotId);
    }

    private static String csvCell(String s) {
        if (s == null) {
            return "";
        }
        String t = s.replace("\r", " ").replace("\n", " ");
        int first = 0;
        while (first < t.length()
                && (Character.isWhitespace(t.charAt(first)) || Character.isISOControl(t.charAt(first)))) {
            first++;
        }
        // CSV quoting protects delimiters, not spreadsheet formula evaluation.
        if (first < t.length() && "=+-@".indexOf(t.charAt(first)) >= 0) {
            t = "'" + t;
        }
        if (t.contains(";") || t.contains("\"")) {
            return '"' + t.replace("\"", "\"\"") + '"';
        }
        return t;
    }

    private static String nvl(String... candidates) {
        for (String c : candidates) {
            if (c != null && !c.isBlank()) {
                return c;
            }
        }
        return "snapshot";
    }
}

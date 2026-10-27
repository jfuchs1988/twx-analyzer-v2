package io.twxav2.api;

import io.quarkus.hibernate.orm.panache.PanacheQuery;
import io.quarkus.panache.common.Page;
import io.quarkus.panache.common.Sort;
import io.twxav2.api.dto.FindingDto;
import io.twxav2.api.dto.ObjectDto;
import io.twxav2.api.dto.RuleBarDto;
import io.twxav2.api.dto.SnapshotDto;
import io.twxav2.api.dto.TypeCountDto;
import io.twxav2.api.dto.ToolkitDto;
import io.twxav2.ingest.TypeRegistry;
import io.twxav2.model.Finding;
import io.twxav2.model.ObjectExclusion;
import io.twxav2.model.Snapshot;
import io.twxav2.model.ToolkitDependency;
import io.twxav2.model.ToolkitNest;
import io.twxav2.model.ToolkitSnapshot;
import io.twxav2.model.TwxObject;
import io.twxav2.service.IngestService;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.resteasy.reactive.MultipartForm;
import org.jboss.resteasy.reactive.RestForm;
import org.jboss.resteasy.reactive.RestQuery;
import org.jboss.resteasy.reactive.multipart.FileUpload;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * REST resource /api/snapshots: upload/replace/re-analyze, listing with health scores,
 * findings with team statuses, objects, exclusions, types, toolkit inventory and
 * dependency graph, app/toolkit conflicts, quality gate and the documentation tasks view.
 */
@Path("/api/snapshots")
@Produces(MediaType.APPLICATION_JSON)
public class SnapshotResource {

    @Inject
    IngestService ingestService;

    @Inject
    io.twxav2.service.IngestQueue ingestQueue;

    @Inject
    EntityManager em;

    public static class UploadForm {
        @RestForm("file")
        public FileUpload file;
    }

    @POST
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    @Transactional
    public Response upload(@MultipartForm UploadForm form) throws Exception {
        if (form.file == null) {
            return Response.status(400).entity(Map.of("error", "Keine Datei gesendet (Feld 'file')")).build();
        }
        String fileName = form.file.fileName();
        if (fileName == null || fileName.isBlank()) {
            return Response.status(400).entity(Map.of("error", "Dateiname fehlt")).build();
        }
        // Re-import of the same file: automatically delete the old snapshot and
        // re-ingest (findings/triage are lost; toolkit dedup remains). A still
        // running import (UPLOADED/PARSED) must not be overwritten -> 409.
        // The old archive files are deleted only after a successful enqueue
        // (otherwise state is lost on a queue 409).
        Snapshot dupByName = Snapshot.find("filename = ?1 and status <> ?2",
                fileName, Snapshot.Status.ERROR).firstResult();
        Long replacedId = null;
        if (dupByName != null) {
            if (dupByName.status != Snapshot.Status.READY) {
                return Response.status(409).entity(Map.of("error",
                        "Datei „" + fileName + "“ wird gerade als Snapshot #" + dupByName.id
                                + " analysiert")).build();
            }
            replacedId = dupByName.id;
        }
        long size = form.file.size();
        // The multipart temp file only lives until the request ends -> copy to our own temp file
        // so ingestion can run in the queue in parallel with the response.
        java.nio.file.Path tmp = java.nio.file.Files.createTempFile("upload-queue-", ".twx");
        java.nio.file.Files.copy(form.file.uploadedFile(), tmp,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        final Long oldId = replacedId;
        Long taskId = ingestQueue.submit(fileName, "file:" + fileName,
                io.twxav2.service.IngestQueue.Phase.UPLOAD, task -> {
                    try {
                        Snapshot s = ingestService.ingest(fileName, size, tmp, task::advance);
                        if (s.status == Snapshot.Status.ERROR) {
                            task.fail(s.statusMessage);
                            return null;
                        }
                        if (oldId != null) {
                            // Trailing cleanup (keep the archive file): if the
                            // replace backstop in ingest() already cleaned up, this
                            // is a no-op; otherwise it catches the predecessor whose
                            // project+version the backstop did not recognize.
                            ingestService.delete(oldId, true);
                        }
                        return s.id;
                    } catch (Exception e) {
                        task.fail(e.getClass().getSimpleName() + ": " + e.getMessage());
                        return null;
                    } finally {
                        try {
                            java.nio.file.Files.deleteIfExists(tmp);
                        } catch (java.io.IOException ignored) {
                        }
                    }
                });
        if (taskId == null) {
            java.nio.file.Files.deleteIfExists(tmp);
            return Response.status(409).entity(Map.of("error",
                    "Datei „" + fileName + "“ liegt bereits in der Warteschlange")).build();
        }
        // The predecessor is deleted only after successful ingestion (job above,
        // same as for re-analysis). Deleting it up front here would collide with the
        // worker transaction's read snapshot (MariaDB: "Record has
        // changed since last read") and just small files would let the job
        // fail — the backstop in IngestService.ingest() is the only deleter.
        return Response.status(202).entity(replacedId == null
                ? Map.of("taskId", taskId)
                : Map.of("taskId", taskId, "replacedSnapshotId", replacedId)).build();
    }

    /**
     * Local re-analysis without a file upload: the snapshot's stored archive file
     * (data/uploads) runs through the same pipeline again. As with the replace
     * import, a new snapshot and new findings are created (triage states are
     * lost); the old snapshot is deleted after a successful enqueue, the
     * archive file is kept.
     */
    @POST
    @Path("{id}/reanalyze")
    @Transactional
    public Response reanalyze(@PathParam("id") Long id) throws Exception {
        Snapshot s = Snapshot.findById(id);
        if (s == null) {
            return Response.status(404).entity(Map.of("error", "Snapshot #" + id + " nicht gefunden")).build();
        }
        if (s.status != Snapshot.Status.READY) {
            return Response.status(409).entity(Map.of("error",
                    "Snapshot #" + id + " ist nicht fertig analysiert (Status „" + s.status + "“)")).build();
        }
        java.nio.file.Path stored = ingestService.findUpload(s);
        if (stored == null) {
            return Response.status(409).entity(Map.of("error",
                    "Gespeicherte Datei zu Snapshot #" + id + " fehlt — TWX erneut hochladen")).build();
        }
        String fileName = s.filename;
        long size = java.nio.file.Files.size(stored);
        Long taskId = ingestQueue.submit(fileName, "file:" + fileName,
                io.twxav2.service.IngestQueue.Phase.UPLOAD, task -> {
                    try {
                        // ingest() moves the file under a new timestamp-based name;
                        // on an error before that the archive is kept, so no deletion.
                        Snapshot n = ingestService.ingest(fileName, size, stored, task::advance);
                        if (n.status == Snapshot.Status.ERROR) {
                            task.fail(n.statusMessage);
                            return null;
                        }
                        return n.id;
                    } catch (Exception e) {
                        task.fail(e.getClass().getSimpleName() + ": " + e.getMessage());
                        return null;
                    }
                });
        if (taskId == null) {
            return Response.status(409).entity(Map.of("error",
                    "Datei „" + fileName + "“ liegt bereits in der Warteschlange")).build();
        }
        // The old snapshot stays visible until the new one reaches READY status; ingest()
        // deletes it itself via the replace backstop (project+version) — so nothing
        // is lost if the app restarts between enqueue and ingestion.
        return Response.status(202)
                .entity(Map.of("taskId", taskId, "replacedSnapshotId", id)).build();
    }

    /**
     * Type separation (UI): type=toolkit only toolkits, type=app process apps
     * (legacy entries without a type count as process apps), otherwise everything.
     */
    @GET
    @Transactional
    public List<SnapshotDto> list(@RestQuery String type) {
        Sort sort = Sort.by("id").descending();
        List<Snapshot> list;
        if ("toolkit".equalsIgnoreCase(type)) {
            list = Snapshot.list("isToolkit = true", sort);
        } else if ("app".equalsIgnoreCase(type)) {
            list = Snapshot.list("isToolkit is null or isToolkit = false", sort);
        } else {
            list = Snapshot.listAll(sort);
        }
        // Health score per snapshot: fetch the sum of finding scores in one query
        java.util.Map<Long, Integer> totals = scoreTotals();
        return list.stream()
                .map(s -> SnapshotDto.of(s, totals.get(s.id)))
                .toList();
    }

    /** Sum of all finding scores per snapshot (for the health score); accepted findings excluded (B5). */
    private java.util.Map<Long, Integer> scoreTotals() {
        java.util.Map<Long, Integer> out = new java.util.HashMap<>();
        for (Object[] r : em.createQuery(
                "select f.snapshot.id, coalesce(sum(f.score), 0) from Finding f "
                        + "where f.status is null or f.status <> :acc group by f.snapshot.id",
                Object[].class)
                .setParameter("acc", Finding.Status.ACCEPTED)
                .getResultList()) {
            out.put((Long) r[0], ((Number) r[1]).intValue());
        }
        return out;
    }

    /**
     * B10: per-snapshot rule hit bars — top-8 rules by hit count
     * (accepted findings excluded, like health). One request for the whole list.
     */
    @GET
    @Path("rule-summary")
    @Transactional
    public java.util.Map<Long, List<RuleBarDto>> ruleSummary() {
        java.util.Map<Long, List<RuleBarDto>> out = new java.util.HashMap<>();
        for (Object[] r : em.createQuery(
                "select f.snapshot.id, f.ruleId, f.severity, count(f) from Finding f "
                        + "where f.status is null or f.status <> :acc "
                        + "group by f.snapshot.id, f.ruleId, f.severity order by count(f) desc",
                Object[].class)
                .setParameter("acc", Finding.Status.ACCEPTED)
                .getResultList()) {
            List<RuleBarDto> bars = out.computeIfAbsent((Long) r[0], k -> new java.util.ArrayList<>());
            if (bars.size() < 8) {
                bars.add(new RuleBarDto((String) r[1], ((Finding.Severity) r[2]).name(),
                        ((Number) r[3]).longValue()));
            }
        }
        return out;
    }

    @GET
    @Path("{id}")
    @Transactional
    public Response get(@PathParam("id") Long id) {
        Snapshot s = Snapshot.findById(id);
        if (s == null) {
            return notFound();
        }
        Integer total = scoreTotals().get(id);
        return Response.ok(SnapshotDto.of(s, total)).build();
    }

    @DELETE
    @Path("{id}")
    public Response delete(@PathParam("id") Long id) {
        return ingestService.delete(id) ? Response.noContent().build() : notFound();
    }

    @GET
    @Path("{id}/findings")
    @Transactional
    public List<FindingDto> findings(@PathParam("id") Long id,
                                     @RestQuery String severity,
                                     @RestQuery String rule,
                                     @RestQuery String status,
                                     @RestQuery @DefaultValue("false") boolean reviewOnly) {
        StringBuilder q = new StringBuilder("snapshot.id = ?1");
        List<Object> params = new ArrayList<>();
        params.add(id);
        if (severity != null && !severity.isBlank()) {
            Finding.Severity sev;
            try {
                sev = Finding.Severity.valueOf(severity.toUpperCase());
            } catch (IllegalArgumentException e) {
                throw new jakarta.ws.rs.WebApplicationException(
                        Response.status(400).entity(Map.of("error", "Unbekannte Schwere: " + severity)).build());
            }
            q.append(" and severity = ?").append(params.size() + 1);
            params.add(sev);
        }
        // Status filter (B5): open also counts null rows (legacy data)
        if (status != null && !status.isBlank()) {
            Finding.Status st;
            try {
                st = Finding.Status.valueOf(status.toUpperCase());
            } catch (IllegalArgumentException e) {
                throw new jakarta.ws.rs.WebApplicationException(
                        Response.status(400).entity(Map.of("error", "Unbekannter Status: " + status)).build());
            }
            if (st == Finding.Status.OPEN) {
                q.append(" and (status is null or status = ?").append(params.size() + 1).append(")");
            } else {
                q.append(" and status = ?").append(params.size() + 1);
            }
            params.add(st);
        }
        // rule as a prefix (R-<KAT>-<nnn>); ruleId additionally contains the title
        if (rule != null && !rule.isBlank()) {
            q.append(" and ruleId like ?").append(params.size() + 1);
            params.add(rule + "%");
        }
        if (reviewOnly) {
            q.append(" and needsReview = true");
        }
        // Ranking as in the analysis: score desc, then severity, then object, then rule
        Sort sort = Sort.by("score").descending().and("severity").ascending()
                .and("objectName").ascending().and("ruleId").ascending();
        return Finding.<Finding>find(q.toString(), sort, params.toArray()).list()
                .stream().map(FindingDto::of).toList();
    }

    /**
     * Bulk status change (B5) using the findings filters of the list. Here rather than in
     * FindingResource because RESTEasy matches the URL against the more specific root
     * /api/snapshots and an /api counterpart would never be reached (was 404).
     */
    @PUT
    @Path("{id}/findings/status")
    @Consumes(MediaType.APPLICATION_JSON)
    @Transactional
    public Response setBulkStatus(@PathParam("id") Long id, FindingResource.BulkStatusForm form) {
        if (Snapshot.findById(id) == null) {
            return Response.status(404).entity(Map.of("error", "Snapshot nicht gefunden")).build();
        }
        int changed = FindingResource.bulkUpdate(id, form);
        return Response.ok(Map.of("changed", changed)).build();
    }

    @GET
    @Path("{id}/objects")
    @Transactional
    public List<ObjectDto> objects(@PathParam("id") Long id,
                                   @RestQuery String q,
                                   @RestQuery String prefix,
                                   @RestQuery String subType,
                                   @RestQuery @DefaultValue("0") int page,
                                   @RestQuery @DefaultValue("100") int size) {
        page = Math.max(page, 0);   // Panache returns negative values as 500
        size = Math.min(Math.max(size, 1), 1000); // cap at the top: one request never > 1000 rows
        StringBuilder query = new StringBuilder("snapshot.id = ?1");
        List<Object> params = new java.util.ArrayList<>(List.of(id));
        if (prefix != null && !prefix.isBlank()) {
            query.append(" and prefix = ?").append(params.size() + 1);
            params.add(prefix);
        }
        if (subType != null && !subType.isBlank()) {
            if ("-".equals(subType)) {   // group without subtype (prefixes not split up)
                query.append(" and subType is null");
            } else {
                query.append(" and subType = ?").append(params.size() + 1);
                params.add(subType);
            }
        }
        if (q != null && !q.isBlank()) {
            query.append(" and (name like ?").append(params.size() + 1)
                 .append(" or id like ?").append(params.size() + 1).append(")");
            params.add("%" + q + "%");
        }
        Sort sort = Sort.by("typeName").ascending().and("name").ascending();
        java.util.Set<String> excluded = exclusionIds(id);
        List<ObjectDto> out = TwxObject.<TwxObject>find(query.toString(), sort, params.toArray())
                .page(Page.of(page, Math.max(size, 1))).list().stream()
                .map(o -> ObjectDto.of(o, excluded.contains(o.id.substring(o.id.indexOf(':') + 1))))
                .toList();
        return out;
    }

    /** TWX IDs of a snapshot's excluded objects (exclude feature). */
    static java.util.Set<String> exclusionIds(Long snapshotId) {
        java.util.Set<String> out = new java.util.HashSet<>();
        for (ObjectExclusion x : ObjectExclusion.<ObjectExclusion>list("snapshot.id", snapshotId)) {
            out.add(x.twxId);
        }
        return out;
    }

    // ---------------- Exclude feature ----------------

    @GET
    @Path("{id}/exclusions")
    @Transactional
    public List<dto.ExclusionDto> exclusions(@PathParam("id") Long id) {
        if (Snapshot.findById(id) == null) {
            return List.of();
        }
        return ObjectExclusion.<ObjectExclusion>list("snapshot.id", id).stream()
                .map(x -> {
                    TwxObject o = TwxObject.findById(id + ":" + x.twxId);
                    String source = x.source == null ? "MANUAL" : x.source;
                    return new dto.ExclusionDto(x.twxId, x.name, o == null ? null : o.typeName,
                            source, x.reason);
                })
                .sorted(java.util.Comparator.comparing((dto.ExclusionDto d) -> d.source())
                        .thenComparing(d -> d.name() == null ? "" : d.name()))
                .toList();
    }

    /**
     * Exclude an object from all rules: the object's existing findings are
     * removed immediately, and the object stays out at every (re-)analysis.
     */
    @PUT
    @Path("{id}/exclusions")
    @Consumes(MediaType.APPLICATION_JSON)
    @Transactional
    public Response exclude(@PathParam("id") Long id, dto.ExclusionReq req) {
        Snapshot s = Snapshot.findById(id);
        if (s == null) {
            return Response.status(404).entity(Map.of("error", "Snapshot nicht gefunden")).build();
        }
        String objId = req == null || req.objectId() == null ? "" : req.objectId().trim();
        TwxObject o = TwxObject.findById(objId);
        if (o == null && !objId.contains(":")) {
            o = TwxObject.findById(s.id + ":" + objId);
        }
        if (o == null) {
            return Response.status(404).entity(Map.of("error", "Objekt nicht gefunden: " + objId)).build();
        }
        String twxId = o.id.substring(o.id.indexOf(':') + 1);
        ObjectExclusion x = ObjectExclusion.find("snapshot.id = ?1 and twxId = ?2", s.id, twxId).firstResult();
        if (x == null) {
            x = new ObjectExclusion();
            x.snapshot = s;
            x.twxId = twxId;
            x.name = o.name;
            x.persist();
        }
        Finding.delete("snapshot.id = ?1 and objectId = ?2", s.id, twxId);
        s.findingCount = (int) Finding.count("snapshot.id", s.id);
        s.persist();
        return Response.ok(new dto.ExclusionDto(twxId, x.name, o.typeName, "MANUAL", null)).build();
    }

    /**
     * Lift an exclusion. The object's findings reappear only after a new
     * analysis (re-analyze) — nothing is recomputed automatically by
     * design.
     */
    @DELETE
    @Path("{id}/exclusions/{twxId: .+}")
    @Transactional
    public Response unexclude(@PathParam("id") Long id, @PathParam("twxId") String twxId) {
        Snapshot s = Snapshot.findById(id);
        if (s == null) {
            return Response.status(404).entity(Map.of("error", "Snapshot nicht gefunden")).build();
        }
        long n = ObjectExclusion.delete("snapshot.id = ?1 and twxId = ?2", s.id, twxId);
        if (n == 0) {
            return Response.status(404).entity(Map.of("error", "Ausschluss nicht gefunden")).build();
        }
        return Response.noContent().build();
    }

    @GET
    @Path("{id}/types")
    @Transactional
    public List<TypeCountDto> types(@PathParam("id") Long id) {
        // prefix groups; prefix groups with subtypes (1, 25) are split per subType
        return em.createQuery("select o.prefix, o.subType, count(o) from TwxObject o "
                + "where o.snapshot.id = ?1 group by o.prefix, o.subType order by count(o) desc", Object[].class)
                .setParameter(1, id).getResultList().stream()
                .map(r -> {
                    String prefix = (String) r[0];
                    String sub = (String) r[1];
                    return new TypeCountDto(prefix, sub,
                            sub != null && !sub.isBlank() ? sub : TypeRegistry.typeName(prefix), (Long) r[2]);
                })
                .toList();
    }

    @GET
    @Path("{id}/toolkits")
    @Transactional
    public List<ToolkitDto> toolkits(@PathParam("id") Long id) {
        List<ToolkitDependency> deps = ToolkitDependency
                .<ToolkitDependency>find("snapshot.id = ?1", Sort.by("rank").ascending(), id).list();
        // Usage evidence (B4.2, after FP audit): "used" = app declaration OR
        // embedding in another imported toolkit (ToolkitNest edge);
        // "no usage detected" = embedded, but neither declared nor depended on.
        java.util.Set<String> rootIds = new java.util.LinkedHashSet<>();
        for (ToolkitDependency d : deps) {
            String cid = d.toolkit != null ? d.toolkit.id : d.toolkitId;
            if (cid != null) {
                rootIds.add(cid);
            }
        }
        java.util.Map<String, List<String>> inbound = new java.util.HashMap<>();
        java.util.Set<String> seen = new java.util.HashSet<>(rootIds);
        java.util.Set<String> level = new java.util.LinkedHashSet<>(rootIds);
        java.util.List<String[]> edges = new ArrayList<>();   // {parentId, childId}
        while (!level.isEmpty()) {
            // Load edges per BFS level in ONE query (instead of per parent toolkit)
            List<ToolkitNest> levelEdges = ToolkitNest.list("parent.id in ?1",
                    new ArrayList<>(level));
            java.util.Set<String> next = new java.util.LinkedHashSet<>();
            for (ToolkitNest n : levelEdges) {
                String cid = n.childToolkitId;
                if (cid == null || n.parent == null || n.parent.id == null) {
                    continue;
                }
                edges.add(new String[]{n.parent.id, cid});
                if (seen.add(cid)) {
                    next.add(cid);
                }
            }
            level = next;
        }
        // Collect parent labels per edge target (load IDs batched, not N+1)
        java.util.Set<String> parentIds = new java.util.HashSet<>();
        for (String[] e : edges) {
            parentIds.add(e[0]);
        }
        java.util.Map<String, String> labelById = new java.util.HashMap<>();
        if (!parentIds.isEmpty()) {
            for (ToolkitSnapshot p : ToolkitSnapshot.<ToolkitSnapshot>list("id in ?1", new ArrayList<>(parentIds))) {
                labelById.put(p.id, p.projectAcronym != null && !p.projectAcronym.isBlank()
                        ? p.projectAcronym : p.id);
            }
        }
        for (String[] e : edges) {
            if (e[0].equals(e[1])) {
                continue;   // self-loop (data error): do not count as usage
            }
            inbound.computeIfAbsent(e[1], k -> new ArrayList<>())
                    .add(labelById.getOrDefault(e[0], e[0]));
        }
        List<ToolkitDto> out = new ArrayList<>();
        for (ToolkitDependency d : deps) {
            String cid = d.toolkit != null ? d.toolkit.id : d.toolkitId;
            boolean declared = d.declaredName != null || d.declaredVersion != null;
            boolean embedded = d.toolkit != null;
            List<String> parents = inbound.getOrDefault(cid, List.of()).stream()
                    .filter(p -> p != null).distinct().toList();
            String usage;
            List<String> evidence = new ArrayList<>();
            if (declared) {
                // The app itself declares the toolkit in its package.xml: that is
                // a real usage (removing it would break the build), not a suspicion.
                usage = "in use";
                evidence.add("declared by the app in package.xml");
                for (String p : parents) {
                    evidence.add("ausserdem Abhaengigkeit von " + p + " (package.xml)");
                }
                if (!embedded) {
                    evidence.add("ZIP is not embedded — contents cannot be checked");
                }
            } else if (!parents.isEmpty()) {
                usage = "in use";
                for (String p : parents) {
                    evidence.add("embedded in imported toolkit '" + p + "' (package.xml)");
                }
            } else if (embedded) {
                usage = "no usage detected";
                evidence.add("embedded but undeclared and dependency-free");
            } else {
                usage = "unknown";
            }
            out.add(ToolkitDto.of(d, usage, evidence));
        }
        return out;
    }

    /**
     * The app's toolkit graph: declared toolkits plus their nested
     * dependencies (recursive, cycle-safe). Conflicts = same acronym
     * with different versions in the same graph (e.g. two toolkits pull
     * the same sub-toolkit in different states).
     */
    @GET
    @Path("{id}/toolkit-graph")
    @Transactional
    public dto.ToolkitGraphDto toolkitGraph(@PathParam("id") Long id) {
        java.util.Map<String, ToolkitDependency> rootDeps = new java.util.LinkedHashMap<>();
        for (ToolkitDependency d : ToolkitDependency
                .<ToolkitDependency>find("snapshot.id = ?1", Sort.by("rank").ascending(), id).list()) {
            String cid = d.toolkit != null ? d.toolkit.id : d.toolkitId;
            if (cid != null) {
                rootDeps.putIfAbsent(cid, d);
            }
        }
        // BFS from manifest edges; depth = first-find depth, edges may repeat (many parents)
        java.util.Map<String, Integer> depth = new java.util.LinkedHashMap<>();
        List<dto.ToolkitGraphEdge> edges = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        java.util.Set<String> seenEdge = new java.util.HashSet<>();
        java.util.Deque<String> frontier = new java.util.ArrayDeque<>();
        for (var e : rootDeps.entrySet()) {
            seen.add(e.getKey());
            seenEdge.add("|" + e.getKey());
            depth.put(e.getKey(), 1);
            frontier.add(e.getKey());
            edges.add(new dto.ToolkitGraphEdge("", e.getKey(), e.getValue().declaredVersion));
        }
        List<ToolkitNest> allNests = new ArrayList<>();
        while (!frontier.isEmpty()) {
            String pid = frontier.poll();
            List<ToolkitNest> nests = ToolkitNest.<ToolkitNest>find("parent.id = ?1", Sort.by("rank").ascending(), pid).list();
            allNests.addAll(nests);
            for (ToolkitNest n : nests) {
                String cid = n.childToolkitId;
                if (cid == null || cid.equals(pid) || !seenEdge.add(pid + "|" + cid)) {
                    continue;
                }
                edges.add(new dto.ToolkitGraphEdge(pid, cid, n.childVersion));
                if (seen.add(cid)) {
                    depth.put(cid, depth.get(pid) + 1);
                    frontier.add(cid);
                }
            }
        }
        List<dto.ToolkitGraphNode> nodes = new ArrayList<>();
        for (var en : depth.entrySet()) {
            String cid = en.getKey();
            ToolkitSnapshot t = ToolkitSnapshot.findById(cid);
            if (t != null) {
                ToolkitDependency rd = rootDeps.get(cid);
                nodes.add(new dto.ToolkitGraphNode(cid, t.projectAcronym, t.projectName, t.version,
                        t.sha256, t.objectCount, en.getValue(), rd != null ? rd.declaredVersion : null, true));
                continue;
            }
            // ZIP never embedded: metadata alone from the declaration
            String acr = null, name = null, ver = null;
            ToolkitDependency rd = rootDeps.get(cid);
            if (rd != null) {
                acr = rd.declaredAcronym;
                name = rd.declaredName;
                ver = rd.declaredVersion;
            } else {
                ToolkitNest nd = ToolkitNest.<ToolkitNest>find("childToolkitId = ?1", cid).firstResult();
                if (nd != null) {
                    acr = nd.childAcronym;
                    name = nd.childName;
                    ver = nd.childVersion;
                }
            }
            nodes.add(new dto.ToolkitGraphNode(cid, acr, name, ver, null, null, en.getValue(), ver, false));
        }
        // Conflicts: acronyms with >1 distinguishable variant (version or content)
        java.util.Map<String, List<dto.ToolkitGraphNode>> byAcr = new java.util.LinkedHashMap<>();
        for (dto.ToolkitGraphNode n : nodes) {
            String key = n.acronym() == null || n.acronym().isBlank() ? n.toolkitId() : n.acronym().toLowerCase();
            byAcr.computeIfAbsent(key, k -> new ArrayList<>()).add(n);
        }
        // Parent labels per child ("App" or the parent acronym)
        java.util.Map<String, String> label = new java.util.HashMap<>();
        for (dto.ToolkitGraphNode n : nodes) {
            label.put(n.toolkitId(), n.acronym() != null && !n.acronym().isBlank() ? n.acronym() : n.toolkitId());
        }
        java.util.Map<String, java.util.Set<String>> users = new java.util.LinkedHashMap<>();
        for (dto.ToolkitGraphEdge e : edges) {
            users.computeIfAbsent(e.childId(), k -> new java.util.LinkedHashSet<>())
                    .add(e.parentId().isEmpty() ? "App" : label.getOrDefault(e.parentId(), e.parentId()));
        }
        List<dto.ToolkitGraphConflict> conflicts = new ArrayList<>();
        for (List<dto.ToolkitGraphNode> group : byAcr.values()) {
            // Column = real version differences in the graph; identical content under
            // a different version name is reported as sameContent, not as a hard conflict.
            java.util.Set<String> versions = new java.util.HashSet<>();
            for (dto.ToolkitGraphNode n : group) {
                versions.add(n.version() == null ? "?" : n.version());
            }
            if (versions.size() < 2) {
                continue;
            }
            boolean sameContent = group.stream().filter(dto.ToolkitGraphNode::known)
                    .map(dto.ToolkitGraphNode::sha256).distinct().count() <= 1;
            List<dto.ToolkitGraphUse> uses = group.stream()
                    .sorted(java.util.Comparator.comparing(dto.ToolkitGraphNode::toolkitId))
                    .map(n -> new dto.ToolkitGraphUse(n.toolkitId(), n.version(), n.sha256(), n.known(),
                            List.copyOf(users.getOrDefault(n.toolkitId(), java.util.Set.of()))))
                    .toList();
            conflicts.add(new dto.ToolkitGraphConflict(group.get(0).acronym() != null
                    ? group.get(0).acronym() : group.get(0).toolkitId(), group.get(0).name(), sameContent, uses));
        }
        conflicts.sort(java.util.Comparator.comparing(dto.ToolkitGraphConflict::acronym,
                String.CASE_INSENSITIVE_ORDER));
        // Cycles in the graph (B4.1): edges between known nodes, app edge excluded
        List<String[]> cycEdges = edges.stream()
                .filter(e -> !e.parentId().isEmpty())
                .map(e -> new String[]{e.parentId(), e.childId()}).toList();
        List<String> cycleNodes = new ArrayList<>(Cycles.cyclicNodes(cycEdges));
        java.util.Collections.sort(cycleNodes);
        return new dto.ToolkitGraphDto(nodes, edges, conflicts, cycleNodes);
    }

    /**
     * Version conflict overview across all apps (toolkit main menu): per
     * analyzed app the conflicts from its toolkit graph, only when there are any.
     */
    @GET
    @Path("toolkit-conflicts")
    @Transactional
    public List<dto.AppToolkitConflicts> appToolkitConflicts() {
        List<dto.AppToolkitConflicts> out = new ArrayList<>();
        for (Snapshot s : Snapshot.<Snapshot>find(
                "status = ?1 order by id desc", Snapshot.Status.READY).list()) {
            if (Boolean.TRUE.equals(s.isToolkit)) {
                continue; // conflicts are relevant per process app
            }
            List<dto.ToolkitGraphConflict> cs = toolkitGraph(s.id).conflicts();
            if (!cs.isEmpty()) {
                String app = s.projectAcronym != null && !s.projectAcronym.isBlank()
                        ? s.projectAcronym : s.projectName;
                out.add(new dto.AppToolkitConflicts(s.id, app, s.snapshotName, cs));
            }
        }
        return out;
    }

    /**
     * Quality gate for pipelines (B9): pass/fail according to
     * thresholds; accepted findings (B5) do not count.
     * Thresholds overridable per query, otherwise properties twxav2.gate.*.
     */
    @GET
    @Path("{id}/gate")
    @Transactional
    public Response gate(@PathParam("id") Long id,
                         @RestQuery Integer maxCritical,
                         @RestQuery Integer maxMajor,
                         @RestQuery Integer minHealth) {
        Snapshot s = Snapshot.findById(id);
        if (s == null) {
            return notFound();
        }
        int limCritical = maxCritical != null ? maxCritical : gateMaxCritical;
        int limMajor = maxMajor != null ? maxMajor : gateMaxMajor;
        int needHealth = minHealth != null ? minHealth : gateMinHealth;
        int critical = (int) countEffective(id, Finding.Severity.CRITICAL, em);
        int major = (int) countEffective(id, Finding.Severity.MAJOR, em);
        long acceptedCount = em.createQuery(
                "select count(f) from Finding f where f.snapshot.id = ?1 and f.status = ?2",
                Long.class).setParameter(1, id).setParameter(2, Finding.Status.ACCEPTED)
                .getSingleResult();
        int totalScore = em.createQuery(
                "select coalesce(sum(f.score), 0) from Finding f where f.snapshot.id = ?1"
                        + " and (f.status is null or f.status <> ?2)", Long.class)
                .setParameter(1, id).setParameter(2, Finding.Status.ACCEPTED)
                .getSingleResult().intValue();
        int health = SnapshotDto.healthScore(totalScore, s.objectCount);
        List<String> reasons = new ArrayList<>();
        // Uniform semantics: negative threshold = "do not check" (already the case for maxMajor)
        if (limCritical >= 0 && critical > limCritical) {
            reasons.add("Critical findings: " + critical + " (allowed " + limCritical + ")");
        }
        if (limMajor >= 0 && major > limMajor) {
            reasons.add("Major findings: " + major + " (allowed " + limMajor + ")");
        }
        if (needHealth >= 0 && health < needHealth) {
            reasons.add("Health-Score: " + health + " (gefordert " + needHealth + ")");
        }
        boolean passed = reasons.isEmpty();
        return Response.ok(new dto.GateDto(id, passed, passed ? 0 : 1, critical, major,
                health, needHealth, limCritical, limMajor, (int) acceptedCount, reasons)).build();
    }

    private static long countEffective(Long snapshotId, Finding.Severity sev, EntityManager em) {
        return em.createQuery("select count(f) from Finding f where f.snapshot.id = :sid"
                + " and f.severity = :sev and (f.status is null or f.status <> :acc)", Long.class)
                .setParameter("sid", snapshotId).setParameter("sev", sev)
                .setParameter("acc", Finding.Status.ACCEPTED).getSingleResult();
    }

    /**
     * Open tasks (B5.2): findings that look like work — findings from
     * rules flagging missing/brief documentation (rule title contains
     * "documentation" or "placeholder") and everything with "TODO" in the text —
     * aggregated per object, with B5 status roles included.
     */
    @GET
    @Path("{id}/tasks")
    @Transactional
    public List<dto.TaskRow> tasks(@PathParam("id") Long id) {
        // ruleId may carry a localized title — check both German and English forms.
        List<Finding> rows = Finding.<Finding>list(
                "snapshot.id = ?1 and (lower(ruleId) like '%documentation%' "
                        + "or lower(ruleId) like '%dokumentation%' "
                        + "or lower(ruleId) like '%placeholder%' "
                        + "or lower(ruleId) like '%platzhalter%' "
                        + "or lower(message) like '%todo%' or lower(location) like '%todo%')",
                Sort.by("objectName").ascending().and("ruleId").ascending(), id);
        java.util.Map<String, int[]> counters = new java.util.LinkedHashMap<>(); // open/resolved/accepted/total
        java.util.Map<String, String[]> labels = new java.util.LinkedHashMap<>();
        java.util.Map<String, java.util.TreeSet<String>> rules = new java.util.LinkedHashMap<>();
        for (Finding f : rows) {
            String key = f.objectId != null ? f.objectId : String.valueOf(f.objectName);
            int[] c = counters.computeIfAbsent(key, k -> new int[4]);
            Finding.Status st = f.status == null ? Finding.Status.OPEN : f.status;
            c[switch (st) {
                case OPEN -> 0;
                case RESOLVED -> 1;
                case ACCEPTED -> 2;
            }]++;
            c[3]++;
            labels.putIfAbsent(key, new String[]{f.objectId, f.objectName});
            rules.computeIfAbsent(key, k -> new java.util.TreeSet<>())
                    .add(f.ruleId.split(" ", 2)[0]);
        }
        List<dto.TaskRow> out = new ArrayList<>();
        for (var e : counters.entrySet()) {
            int[] c = e.getValue();
            String[] label = labels.get(e.getKey());
            out.add(new dto.TaskRow(label[0], label[1], c[0], c[1], c[2], c[3],
                    String.join(", ", rules.get(e.getKey()))));
        }
        return out;
    }

    @org.eclipse.microprofile.config.inject.ConfigProperty(name = "twxav2.gate.max-critical", defaultValue = "0")
    int gateMaxCritical;

    @org.eclipse.microprofile.config.inject.ConfigProperty(name = "twxav2.gate.max-major", defaultValue = "-1")
    int gateMaxMajor;

    @org.eclipse.microprofile.config.inject.ConfigProperty(name = "twxav2.gate.min-health", defaultValue = "70")
    int gateMinHealth;

    private static Response notFound() {
        return Response.status(404).entity(Map.of("error", "Snapshot nicht gefunden")).build();
    }
}

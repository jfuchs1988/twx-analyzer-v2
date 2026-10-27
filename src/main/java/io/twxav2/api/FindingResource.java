package io.twxav2.api;

import io.twxav2.api.dto.FindingDto;
import io.twxav2.model.Finding;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.time.Instant;
import java.util.Map;

/**
 * Team status per finding (B5): open / resolved / accepted, optionally with a comment.
 * Accepted findings stay out of the health score, gate, and KPIs;
 * resolved findings still count (traceability: it really was a problem).
 * Bulk endpoint per snapshot with the same filter as the findings list.
 */
@Path("/api")
@Produces(MediaType.APPLICATION_JSON)
public class FindingResource {

    public static class StatusForm {
        public String status;         // OPEN | RESOLVED | ACCEPTED
        public String comment;
    }

    /** Bulk status change filter; empty fields = no filter (all findings of the snapshot). */
    public static class BulkStatusForm extends StatusForm {
        public String severity;       // e.g. MINOR
        public String rule;           // prefix, e.g. R-SVC-001
        public String objectId;
        public Boolean needsReview;
    }

    @PUT
    @Path("/findings/{id}/status")
    @Consumes(MediaType.APPLICATION_JSON)
    @Transactional
    public Response setStatus(@PathParam("id") Long id, StatusForm form) {
        Finding f = Finding.findById(id);
        if (f == null) {
            return Response.status(404).entity(Map.of("error", "Befund nicht gefunden")).build();
        }
        Finding.Status st = parseStatus(form == null ? null : form.status);
        if (st == null) {
            return bad("Unbekannter Status (erlaubt: OPEN, RESOLVED, ACCEPTED)");
        }
        apply(f, st, form.comment);
        return Response.ok(FindingDto.of(f)).build();
    }

    /**
     * Bulk status change in the snapshot (same filters as GET /snapshots/{id}/findings).
     * Called by SnapshotResource PUT /api/snapshots/{id}/findings/status —
     * as a static helper, because a second @Path("/api/snapshots") parser would never
     * serve the same URL to this class (RESTEasy root matching).
     */
    static int bulkUpdate(Long snapshotId, BulkStatusForm form) {
        Finding.Status st = parseStatus(form == null ? null : form.status);
        if (st == null) {
            throw new jakarta.ws.rs.WebApplicationException(
                    bad("Unbekannter Status (erlaubt: OPEN, RESOLVED, ACCEPTED)"));
        }
        StringBuilder q = new StringBuilder("snapshot.id = ?1");
        java.util.List<Object> params = new java.util.ArrayList<>();
        params.add(snapshotId);
        if (notBlank(form.severity)) {
            Finding.Severity sev;
            try {
                sev = Finding.Severity.valueOf(form.severity.toUpperCase());
            } catch (IllegalArgumentException e) {
                throw new jakarta.ws.rs.WebApplicationException(bad("Unbekannte Schwere: " + form.severity));
            }
            q.append(" and severity = ?").append(params.size() + 1);
            params.add(sev);
        }
        if (notBlank(form.rule)) {
            q.append(" and ruleId like ?").append(params.size() + 1);
            params.add(form.rule.trim() + "%");
        }
        if (notBlank(form.objectId)) {
            q.append(" and objectId = ?").append(params.size() + 1);
            params.add(form.objectId);
        }
        if (form.needsReview != null) {
            q.append(" and needsReview = ?").append(params.size() + 1);
            params.add(form.needsReview);
        }
        int changed = 0;
        for (Finding f : Finding.<Finding>list(q.toString(), params.toArray())) {
            apply(f, st, form.comment);
            changed++;
        }
        return changed;
    }

    static void apply(Finding f, Finding.Status st, String comment) {
        f.status = st;
        f.statusComment = comment == null || comment.isBlank() ? null
                : comment.length() > 1000 ? comment.substring(0, 1000) : comment;
        f.statusAt = Instant.now();
        f.persist();
    }

    private static Finding.Status parseStatus(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Finding.Status.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static Response bad(String message) {
        return Response.status(400).entity(Map.of("error", message)).build();
    }
}

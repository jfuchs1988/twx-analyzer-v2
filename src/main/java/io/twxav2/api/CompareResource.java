package io.twxav2.api;

import io.twxav2.analysis.diff.LineDiff;
import io.twxav2.api.dto.CompareDto;
import io.twxav2.api.dto.ContentDiffDto;
import io.twxav2.api.dto.ObjectDiff;
import io.twxav2.api.dto.RuleDelta;
import io.twxav2.api.dto.SnapshotDto;
import io.twxav2.model.Finding;
import io.twxav2.model.Snapshot;
import io.twxav2.model.TwxObject;
import jakarta.persistence.EntityManager;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Version comparison (B6): two snapshots against each other - object additions/removals
 * (same object ID, different SHA-256 = changed) and finding
 * trend per rule (accepted findings excluded, like health/gate).
 */
@Path("/api/compare")
@Produces(MediaType.APPLICATION_JSON)
public class CompareResource {

    @Inject
    EntityManager em;

    @GET
    @Transactional
    public Response compare(@QueryParam("base") Long baseId, @QueryParam("target") Long targetId) {
        if (baseId == null || targetId == null || baseId.equals(targetId)) {
            return Response.status(400)
                    .entity(Map.of("error", "Zwei verschiedene Snapshots als base und target noetig")).build();
        }
        Snapshot base = Snapshot.findById(baseId);
        Snapshot target = Snapshot.findById(targetId);
        if (base == null || target == null) {
            return Response.status(404).entity(Map.of("error", "Snapshot nicht gefunden")).build();
        }

        Map<String, TwxObject> baseObjects = objectsOf(baseId);
        Map<String, TwxObject> targetObjects = objectsOf(targetId);
        List<ObjectDiff> added = new ArrayList<>();
        List<ObjectDiff> removed = new ArrayList<>();
        List<ObjectDiff> changed = new ArrayList<>();
        for (var e : targetObjects.entrySet()) {
            TwxObject b = baseObjects.get(e.getKey());
            if (b == null) {
                added.add(diff(e.getValue()));
            } else if (b.sha256 != null && e.getValue().sha256 != null
                    && !b.sha256.equals(e.getValue().sha256)) {
                changed.add(diff(e.getValue()));
            }
        }
        for (var e : baseObjects.entrySet()) {
            if (!targetObjects.containsKey(e.getKey())) {
                removed.add(diff(e.getValue()));
            }
        }

        Map<String, long[]> rules = new TreeMap<>(); // rule4 -> [baseCount, targetCount, baseScore, targetScore]
        accumulateRules(rules, baseId, 0);
        accumulateRules(rules, targetId, 1);
        List<RuleDelta> deltas = new ArrayList<>();
        for (var e : rules.entrySet()) {
            long[] v = e.getValue();
            deltas.add(new RuleDelta(e.getKey(), v[0], v[1], (int) v[2], (int) v[3]));
        }
        deltas.sort((a, b) -> Long.compare(Math.abs(b.targetCount() - b.baseCount())
                        * 1_000_000L + b.targetCount(),
                Math.abs(a.targetCount() - a.baseCount()) * 1_000_000L + a.targetCount()));

        long baseFindings = effectiveCount(baseId);
        long targetFindings = effectiveCount(targetId);
        int baseScore = effectiveScore(baseId);
        int targetScore = effectiveScore(targetId);
        int baseHealth = SnapshotDto.healthScore(baseScore, Math.max(1, base.objectCount));
        int targetHealth = SnapshotDto.healthScore(targetScore, Math.max(1, target.objectCount));

        return Response.ok(new CompareDto(
                SnapshotDto.of(base, baseScore), SnapshotDto.of(target, targetScore),
                added, removed, changed, deltas,
                baseFindings, targetFindings, baseScore, targetScore, baseHealth, targetHealth)).build();
    }

    private static Map<String, TwxObject> objectsOf(Long snapshotId) {
        Map<String, TwxObject> out = new LinkedHashMap<>();
        for (TwxObject o : TwxObject.<TwxObject>find("snapshot.id = ?1", snapshotId).list()) {
            // The DB ID carries the snapshot prefix ("17:1.<uuid>") - for the comparison
            // what counts is the worldwide object ID of the TWX without it.
            int colon = o.id.indexOf(':');
            out.put(colon >= 0 ? o.id.substring(colon + 1) : o.id, o);
        }
        return out;
    }

    /**
     * XML detail diff (B6 addendum): line diff of an object between base
     * and target. objectId is the DB ID of one side ("105:25.<uuid>") - the
     * worldwide ID (without snapshot prefix) looks up both sides; if one object is
     * missing, the existing content is shown in full on one side. Large objects
     * without stored content (cap 1 MB) return a note instead of lines.
     */
    @GET
    @Path("object")
    @Transactional
    public Response objectDiff(@QueryParam("base") Long baseId, @QueryParam("target") Long targetId,
                               @QueryParam("objectId") String objectId) {
        if (baseId == null || targetId == null || objectId == null || objectId.isBlank()) {
            return Response.status(400)
                    .entity(Map.of("error", "base, target, and objectId are required")).build();
        }
        int colon = objectId.indexOf(':');
        String world = colon >= 0 ? objectId.substring(colon + 1) : objectId;
        TwxObject base = TwxObject.findById(baseId + ":" + world);
        TwxObject target = TwxObject.findById(targetId + ":" + world);
        if (base == null && target == null) {
            return Response.status(404).entity(Map.of("error", "Object not found in either snapshot")).build();
        }
        String baseContent = base != null ? base.content : null;
        String targetContent = target != null ? target.content : null;

        LineDiff.Result d;
        String note = null;
        if (baseContent == null && targetContent == null) {
            d = new LineDiff.Result(List.of(), 0, 0, false,
                    "No comparable content: both object versions exceed the "
                            + "1 MB storage limit (large object).");
        } else if (base == null) {
            d = LineDiff.allLines(targetContent, false);
            note = "Object exists only in target (new).";
        } else if (target == null) {
            d = LineDiff.allLines(baseContent, true);
            note = "Object exists only in base (removed).";
        } else if (baseContent == null || targetContent == null) {
            d = new LineDiff.Result(List.of(), 0, 0, false,
                    "Diff unavailable: at least one version exceeds the "
                            + "1 MB storage limit (large object).");
        } else {
            d = LineDiff.diff(baseContent, targetContent);
        }
        return Response.ok(new ContentDiffDto(
                base != null ? base.id : null, target != null ? target.id : null,
                base != null ? base.name : null, target != null ? target.name : null,
                base != null ? base.contentBytes : 0, target != null ? target.contentBytes : 0,
                d.additions(), d.deletions(), d.truncated(),
                note != null ? note : d.note(), d.lines())).build();
    }

    /** Rule rollup (ID prefix before the title) of a snapshot; column offset: 0=base, 1=target. */
    private void accumulateRules(Map<String, long[]> rules, Long snapshotId, int off) {
        // ruleId stores "R-<KAT>-<nnn> <title>"; roll up by the ID part so same-rule
        // findings with differing title text (e.g. legacy rows) merge correctly.
        for (Object[] r : em.createQuery(
                "select f.ruleId, count(f), coalesce(sum(f.score), 0) from Finding f "
                        + "where f.snapshot.id = ?1 and (f.status is null or f.status <> ?2) "
                        + "group by f.ruleId", Object[].class)
                .setParameter(1, snapshotId).setParameter(2, Finding.Status.ACCEPTED)
                .getResultList()) {
            String rule = ((String) r[0]).split(" ", 2)[0];
            long[] v = rules.computeIfAbsent(rule, k -> new long[4]);
            v[off] += ((Number) r[1]).longValue();
            v[2 + off] += ((Number) r[2]).longValue();
        }
    }

    private long effectiveCount(Long snapshotId) {
        return em.createQuery("select count(f) from Finding f where f.snapshot.id = ?1 "
                + "and (f.status is null or f.status <> ?2)", Long.class)
                .setParameter(1, snapshotId).setParameter(2, Finding.Status.ACCEPTED)
                .getSingleResult();
    }

    private int effectiveScore(Long snapshotId) {
        return em.createQuery("select coalesce(sum(f.score), 0) from Finding f "
                + "where f.snapshot.id = ?1 and (f.status is null or f.status <> ?2)", Long.class)
                .setParameter(1, snapshotId).setParameter(2, Finding.Status.ACCEPTED)
                .getSingleResult().intValue();
    }

    private static ObjectDiff diff(TwxObject o) {
        return new ObjectDiff(o.id, o.name, o.typeName, o.prefix);
    }
}

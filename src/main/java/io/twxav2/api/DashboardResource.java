package io.twxav2.api;

import io.quarkus.panache.common.Sort;
import io.twxav2.api.dto.DashboardPoint;
import io.twxav2.api.dto.DashboardProject;
import io.twxav2.api.dto.SnapshotDto;
import io.twxav2.model.Finding;
import io.twxav2.model.Snapshot;
import jakarta.persistence.EntityManager;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Project dashboard (B5.3): per project (acronym or name as the key), the
 * health trend across all analyses plus open critical findings of the
 * latest analysis. Accepted findings (B5) do not count as open.
 */
@Path("/api/dashboard")
@Produces(MediaType.APPLICATION_JSON)
public class DashboardResource {

    @Inject
    EntityManager em;

    @GET
    @Transactional
    public List<DashboardProject> dashboard() {
        List<Snapshot> snaps = Snapshot.<Snapshot>findAll(Sort.by("id").ascending()).list();
        // Score totals per snapshot without accepted findings
        Map<Long, Integer> scoreTotals = new LinkedHashMap<>();
        for (Object[] r : em.createQuery(
                "select f.snapshot.id, coalesce(sum(f.score), 0) from Finding f "
                        + "where f.status is null or f.status <> :acc group by f.snapshot.id",
                Object[].class)
                .setParameter("acc", Finding.Status.ACCEPTED).getResultList()) {
            scoreTotals.put((Long) r[0], ((Number) r[1]).intValue());
        }
        Map<String, List<Snapshot>> byProject = new LinkedHashMap<>();
        for (Snapshot s : snaps) {
            byProject.computeIfAbsent(projectKey(s), k -> new ArrayList<>()).add(s);
        }
        List<DashboardProject> out = new ArrayList<>();
        for (var e : byProject.entrySet()) {
            List<Snapshot> list = e.getValue(); // older first (sort id asc)
            // "latest" = last successful analysis; a failed one (ERROR) should
            // neither hijack the health badge nor pull the critical counter to 0
            Snapshot latest = list.get(list.size() - 1);
            for (int i = list.size() - 1; i >= 0; i--) {
                if (list.get(i).status != Snapshot.Status.ERROR) {
                    latest = list.get(i);
                    break;
                }
            }
            List<DashboardPoint> trend = new ArrayList<>();
            for (Snapshot s : list) {
                Integer total = scoreTotals.get(s.id);
                Integer health = total == null ? null
                        : SnapshotDto.healthScore(total, Math.max(1, s.objectCount));
                trend.add(new DashboardPoint(s.id, s.snapshotName, health, String.valueOf(s.uploadedAt)));
            }
            long openCritical = em.createQuery(
                    "select count(f) from Finding f where f.snapshot.id = :sid "
                            + "and f.severity = :sev and (f.status is null or f.status <> :acc)", Long.class)
                    .setParameter("sid", latest.id)
                    .setParameter("sev", Finding.Severity.CRITICAL)
                    .setParameter("acc", Finding.Status.ACCEPTED)
                    .getSingleResult();
            // Size metrics of the latest analysis (tile line): services and
            // top-level BPDs from the object inventory, script lines denormalized on Snapshot
            int serviceCount = 0;
            int bpdCount = 0;
            for (Object[] r : em.createQuery(
                    "select o.subType, count(o) from TwxObject o where o.snapshot.id = :sid "
                            + "and o.typeName in ('process', 'bpd') group by o.subType", Object[].class)
                    .setParameter("sid", latest.id).getResultList()) {
                if ("service".equals(r[0])) {
                    serviceCount = ((Number) r[1]).intValue();
                } else if ("bpd".equals(r[0])) {
                    bpdCount = ((Number) r[1]).intValue();
                }
            }
            Integer lt = scoreTotals.get(latest.id);
            Integer latestHealth = lt == null ? null
                    : SnapshotDto.healthScore(lt, Math.max(1, latest.objectCount));
            out.add(new DashboardProject(e.getKey(),
                    blankToNull(latest.projectAcronym), blankToNull(latest.projectName),
                    trend, latest.id, latest.snapshotName, latestHealth, openCritical,
                    String.valueOf(latest.uploadedAt), Boolean.TRUE.equals(latest.isToolkit),
                    serviceCount, bpdCount, latest.scriptLines));
        }
        // Process apps first, then toolkits; within each group alphabetical by acronym/name
        out.sort((a, b) -> {
            int byType = Boolean.compare(a.toolkit(), b.toolkit());
            if (byType != 0) {
                return byType;
            }
            return String.CASE_INSENSITIVE_ORDER.compare(displayName(a), displayName(b));
        });
        return out;
    }

    /** Sort key without NPE: acronym -> name -> group key (filename fallback). */
    private static String displayName(DashboardProject p) {
        if (p.acronym() != null) {
            return p.acronym();
        }
        if (p.name() != null) {
            return p.name();
        }
        return p.key() != null ? p.key() : "";
    }

    private static String projectKey(Snapshot s) {
        // group case-insensitively: otherwise "Proj"/"PROJ" yields two tiles (like ToolkitResource)
        if (s.projectAcronym != null && !s.projectAcronym.isBlank()) {
            return s.projectAcronym.toLowerCase();
        }
        return s.projectName != null && !s.projectName.isBlank()
                ? s.projectName.toLowerCase() : s.filename;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}

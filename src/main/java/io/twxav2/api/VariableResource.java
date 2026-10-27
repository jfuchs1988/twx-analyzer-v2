package io.twxav2.api;

import io.twxav2.api.dto.VariableRow;
import io.twxav2.api.dto.VariableUsageRow;
import io.twxav2.model.Snapshot;
import io.twxav2.model.VariableUsage;
import io.twxav2.service.IngestService;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.jboss.resteasy.reactive.RestQuery;

import java.util.ArrayList;
import java.util.List;

/**
 * Variable catalog and usage (tw.local.x, tw.processVariables.x, …).
 * Data comes from the ingest scan (variable_usage), no runtime full-text search.
 */
@Path("/api/variables")
@Produces(MediaType.APPLICATION_JSON)
public class VariableResource {

    private static final int CATALOG_LIMIT = 500;
    private static final int USAGE_LIMIT = 500;

    @Inject
    EntityManager em;

    @Inject
    IngestService ingest;

    @GET
    @Transactional
    public List<VariableRow> catalog(@RestQuery Long snapshotId,
                                     @RestQuery String scope,
                                     @RestQuery String q) {
        StringBuilder jpql = new StringBuilder(
                "select v.snapshot.id, v.scope, v.variable, "
                        + "sum(case when v.assign then 1 else 0 end), "
                        + "sum(case when v.assign then 0 else 1 end), "
                        + "count(distinct v.objectId) "
                        + "from VariableUsage v where 1=1");
        if (snapshotId != null) {
            jpql.append(" and v.snapshot.id = :sid");
        }
        if (scope != null && !scope.isBlank()) {
            jpql.append(" and v.scope = :scope");
        }
        if (q != null && !q.isBlank()) {
            jpql.append(" and lower(v.variable) like :q");
        }
        jpql.append(" group by v.snapshot.id, v.scope, v.variable order by count(v.id) desc");
        var query = em.createQuery(jpql.toString(), Object[].class);
        if (snapshotId != null) {
            query.setParameter("sid", snapshotId);
        }
        if (scope != null && !scope.isBlank()) {
            query.setParameter("scope", scope);
        }
        if (q != null && !q.isBlank()) {
            query.setParameter("q", "%" + q.trim().toLowerCase() + "%");
        }
        List<Object[]> rows = query.setMaxResults(CATALOG_LIMIT).getResultList();

        // Look up snapshot labels (small set, census only when needed)
        java.util.Map<Long, String> labels = new java.util.HashMap<>();
        List<VariableRow> out = new ArrayList<>();
        for (Object[] r : rows) {
            Long sid = ((Number) r[0]).longValue();
            String label = labels.computeIfAbsent(sid, id -> {
                Snapshot s = Snapshot.findById(id);
                return s == null ? "#" + id
                        : (s.projectAcronym != null ? s.projectAcronym + " " : "") + s.snapshotName;
            });
            out.add(new VariableRow(String.valueOf(sid), label, (String) r[1], (String) r[2],
                    ((Number) r[3]).longValue(), ((Number) r[4]).longValue(),
                    ((Number) r[5]).longValue()));
        }
        return out;
    }

    @GET
    @Path("usage")
    @Transactional
    public List<VariableUsageRow> usage(@RestQuery Long snapshotId,
                                        @RestQuery String variable,
                                        @RestQuery String scope) {
        if (snapshotId == null || variable == null || variable.isBlank()) {
            return List.of();
        }
        String jq = "snapshot.id = ?1 and lower(variable) = ?2";
        List<VariableUsage> rows;
        if (scope != null && !scope.isBlank()) {
            rows = VariableUsage.<VariableUsage>find(jq + " and scope = ?3",
                            order(), snapshotId, variable.trim().toLowerCase(), scope)
                    .page(0, USAGE_LIMIT).list();
        } else {
            rows = VariableUsage.<VariableUsage>find(jq, order(),
                            snapshotId, variable.trim().toLowerCase())
                    .page(0, USAGE_LIMIT).list();
        }
        List<VariableUsageRow> out = new ArrayList<>();
        for (VariableUsage v : rows) {
            out.add(new VariableUsageRow(v.objectId, v.objectName, v.prefix, v.scope,
                    v.line, v.assign, v.lineText));
        }
        return out;
    }

    private static io.quarkus.panache.common.Sort order() {
        return io.quarkus.panache.common.Sort.by("objectId").ascending().and("line").ascending();
    }

    /** One-time retroactive scan of all existing snapshots (old ingests without variable_usage). */
    @POST
    @Path("backfill")
    @io.smallrye.common.annotation.Blocking   // long-running scan (~min) — never on the event loop
    public int backfill() {
        return ingest.backfillVariableUsage();
    }
}

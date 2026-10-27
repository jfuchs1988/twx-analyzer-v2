package io.twxav2.api;

import io.quarkus.panache.common.Sort;
import io.twxav2.api.dto.ToolkitInventoryDto;
import io.twxav2.api.dto.ToolkitNestedDto;
import io.twxav2.model.Snapshot;
import io.twxav2.model.ToolkitNest;
import io.twxav2.model.ToolkitSnapshot;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.resteasy.reactive.RestQuery;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Global toolkit inventory (SHA-256 dedup store) with usage-reference counting.
 * latestOnly=true counts only each app project's newest analysis (max id,
 * equivalent to "latest reload" in the snapshot list) as a user.
 * Deletion: single entries or all unused ones; dependent edges are
 * resolved cleanly (usage edges set to "declared only", own
 * sub-toolkit edges dropped, child edges from others remain as
 * "not in inventory" declarations). A ZIP embedded again during the
 * next ingest is recreated normally.
 */
@Path("/api/toolkits")
@Produces(MediaType.APPLICATION_JSON)
public class ToolkitResource {

    @Inject
    EntityManager em;

    @GET
    @Transactional
    public List<ToolkitInventoryDto> list(@RestQuery @DefaultValue("false") boolean latestOnly) {
        String jpql = "select d.toolkit.id, count(distinct d.snapshot.id) from ToolkitDependency d "
                + "where d.toolkit is not null";
        if (latestOnly) {
            jpql += " and d.snapshot.id in (select max(s.id) from Snapshot s where s.status <> :err"
                    + " group by coalesce(nullif(lower(s.projectAcronym), ''), nullif(lower(s.projectName), ''), lower(s.filename)))";
        }
        jpql += " group by d.toolkit.id";
        var query = em.createQuery(jpql, Object[].class);
        if (latestOnly) {
            query.setParameter("err", Snapshot.Status.ERROR);
        }
        Map<String, Long> usage = new HashMap<>();
        for (Object[] r : query.getResultList()) {
            usage.put((String) r[0], (Long) r[1]);
        }
        // Load the full inventory (ID map avoids N+1; also serves
        // determining the "latest known version" per toolkit acronym).
        List<ToolkitSnapshot> all = ToolkitSnapshot.listAll(Sort.by("projectAcronym").ascending()
                .and("version").ascending());
        Map<String, ToolkitSnapshot> byId = new HashMap<>();
        Map<String, String> latestByKey = new HashMap<>();
        for (ToolkitSnapshot t : all) {
            byId.put(t.id, t);
            String key = Versions.key(t.projectAcronym, t.projectName);
            if (key != null) {
                String cur = latestByKey.get(key);
                if (Versions.compare(t.version, cur) > 0) {
                    latestByKey.put(key, t.version);
                }
            }
        }
        // Nested dependencies per toolkit (package.xml of the ZIP);
        // legacy data may contain duplicate rows -> deduplicated here.
        Map<String, List<ToolkitNestedDto>> nested = new HashMap<>();
        java.util.Set<String> seenNest = new java.util.HashSet<>();
        List<String[]> cycleEdges = new ArrayList<>();
        for (ToolkitNest n : ToolkitNest.<ToolkitNest>listAll(Sort.by("rank").ascending())) {
            if (!seenNest.add(n.parent.id + "|" + n.childToolkitId + "|" + n.childVersion)) {
                continue;
            }
            ToolkitSnapshot child = byId.get(n.childToolkitId);
            String childVersionActual = child != null ? child.version : null;
            String childLatest = latestByKey.get(Versions.key(
                    child != null ? child.projectAcronym : n.childAcronym,
                    child != null ? child.projectName : n.childName));
            // Rating: known child by real ZIP version, otherwise by the
            // declared state ("possibly outdated" — the ZIP was never inspectable).
            String effective = child != null ? childVersionActual : n.childVersion;
            boolean outdated = effective != null && childLatest != null
                    && Versions.compare(effective, childLatest) < 0;
            nested.computeIfAbsent(n.parent.id, k -> new ArrayList<>()).add(new ToolkitNestedDto(
                    n.childToolkitId, n.childAcronym, n.childName, n.childVersion,
                    childVersionActual, child != null ? child.sha256 : null, child != null,
                    childLatest, outdated, false));
            if (child != null) {
                cycleEdges.add(new String[]{n.parent.id, n.childToolkitId});
            }
        }
        // Cycle marking (B4.1): global view, edges only between known ZIPs.
        java.util.Set<String> cyclic = Cycles.cyclicNodes(cycleEdges);
        for (List<ToolkitNestedDto> list : nested.values()) {
            list.replaceAll(d -> d.childInCycle() || cyclic.contains(d.childToolkitId())
                    ? new ToolkitNestedDto(d.childToolkitId(), d.childAcronym(), d.childName(),
                            d.childVersion(), d.childVersionActual(), d.childSha256(), d.known(),
                            d.childVersionLatest(), d.childOutdated(), true)
                    : d);
        }
        return all.stream()
                .map(t -> new ToolkitInventoryDto(t.id, t.projectAcronym, t.projectName, t.version,
                        t.sha256, t.objectCount, usage.getOrDefault(t.id, 0L), String.valueOf(t.firstSeen),
                        nested.getOrDefault(t.id, List.of()), cyclic.contains(t.id)))
                .toList();
    }

    @DELETE
    @Path("{id}")
    @Transactional
    public Response delete(@PathParam("id") String id) {
        ToolkitSnapshot t = ToolkitSnapshot.findById(id);
        if (t == null) {
            return Response.status(404).entity(Map.of("error", "Toolkit-Eintrag nicht gefunden")).build();
        }
        detachDependencies(id);
        removeNestEdges(id);
        t.delete();
        return Response.noContent().build();
    }

    /** Deletes all inventory entries without a usage reference (snapshot absent or deleted). */
    @DELETE
    @Path("unused")
    @Transactional
    public Response deleteUnused() {
        List<String> ids = em.createQuery(
                "select t.id from ToolkitSnapshot t where t.id not in"
                        + " (select d.toolkit.id from ToolkitDependency d where d.toolkit is not null)",
                String.class)
                .getResultList();
        if (!ids.isEmpty()) {
            em.createQuery("delete from ToolkitNest n where n.parent.id in :ids")
                    .setParameter("ids", ids).executeUpdate();
            em.createQuery("delete from ToolkitSnapshot t where t.id in :ids")
                    .setParameter("ids", ids).executeUpdate();
        }
        return Response.ok(Map.of("deleted", ids.size())).build();
    }

    /** Usage edges to the entry become "declared only" (toolkit=null, ZIP never embedded). */
    private void detachDependencies(String toolkitId) {
        em.createQuery("update ToolkitDependency d set d.toolkit = null where d.toolkit.id = :id")
                .setParameter("id", toolkitId).executeUpdate();
    }

    /** Sub-toolkit edges of the deleted ZIP are dropped; child edges of others remain (declaration). */
    private void removeNestEdges(String toolkitId) {
        em.createQuery("delete from ToolkitNest n where n.parent.id = :id")
                .setParameter("id", toolkitId).executeUpdate();
    }
}

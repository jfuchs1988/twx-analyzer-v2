package io.twxav2.api;

import io.twxav2.analysis.details.ObjectDetails;
import io.twxav2.analysis.diagram.Diagram;
import io.twxav2.analysis.script.ScriptScanner;
import io.twxav2.api.dto.DiagramDto;
import io.twxav2.api.dto.ObjectContentDto;
import io.twxav2.api.dto.ObjectDetailDto;
import io.twxav2.api.dto.RefRow;
import io.twxav2.api.dto.ScriptDto;
import io.twxav2.ingest.TwxParseResult.ParsedObject;
import io.twxav2.model.TwxObject;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * REST resource /api/objects: object detail view (with resolved references),
 * raw content, script listing and SVG process diagram.
 */
@Path("/api/objects")
@Produces(MediaType.APPLICATION_JSON)
public class ObjectResource {

    /** TWX-internal object references: "<praefix>.<uuid>" (often with a leading slash). */
    private static final Pattern REF_TOKEN =
            Pattern.compile("\\b(\\d{1,4})\\.([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}"
                    + "-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})\\b");
    private static final int MAX_REFS = 100;

    @Inject
    EntityManager em;

    /** ID format: "<snapshotId>:<praefix>.<uuid>" -> regex for the path parameter. */
    @GET
    @Path("{id: .+}")
    @Transactional
    public ObjectContentDto content(@PathParam("id") String id) {
        TwxObject o = TwxObject.findById(id);
        if (o == null) {
            throw new NotFoundException("Objekt nicht gefunden: " + id);
        }
        return new ObjectContentDto(o.id, o.name, o.typeName, o.subType, o.content);
    }

    /**
     * Structured detail view (B7): model properties per object type plus
     * references in both directions. Outgoing references: tokens in the object's own
     * content, resolved against the same snapshot; incoming: LIKE search of the
     * object token within the snapshot (database-side, limit 100 rows).
     */
    @GET
    @Path("{id: .+}/detail")
    @Transactional
    public ObjectDetailDto detail(@PathParam("id") String id) {
        TwxObject o = TwxObject.findById(id);
        if (o == null) {
            throw new NotFoundException("Objekt nicht gefunden: " + id);
        }
        List<ObjectDetails.Section> sections = o.content == null ? List.of()
                : ObjectDetails.sections(toParsed(o));

        int inCount = 0;
        int outCount = 0;
        List<RefRow> outRefs = List.of();
        List<RefRow> inRefs = List.of();
        if (o.content != null) {
            Set<String> tokens = new LinkedHashSet<>();
            Matcher m = REF_TOKEN.matcher(o.content);
            while (m.find() && tokens.size() < 500) {
                tokens.add(m.group(1) + "." + m.group(2));
            }
            tokens.remove(globalId(o)); // ignore self-reference
            outCount = tokens.size();
            if (!tokens.isEmpty()) {
                List<String> dbIds = tokens.stream()
                        .map(t -> o.snapshot.id + ":" + t).toList();
                outRefs = TwxObject.<TwxObject>find("id in ?1", dbIds).list().stream()
                        .map(r -> new RefRow(r.id, r.name, displayType(r)))
                        .limit(MAX_REFS).toList();
            }
            List<Object[]> rows = em.createNativeQuery(
                            "select id, name, `typeName`, `subType` from twx_object "
                                    + "where snapshot_id = ?1 and id <> ?2 "
                                    + "and content like concat('%', ?3, '%') limit " + MAX_REFS)
                    .setParameter(1, o.snapshot.id)
                    .setParameter(2, o.id)
                    .setParameter(3, globalId(o))
                    .getResultList();
            inCount = rows.size();
            inRefs = rows.stream()
                    .map(r -> new RefRow((String) r[0], (String) r[1],
                            r[3] != null && !((String) r[3]).isBlank() ? (String) r[3] : (String) r[2]))
                    .toList();
        }
        return new ObjectDetailDto(o.id, o.prefix, o.typeName, o.subType, o.name, o.description,
                o.snapshot.id, o.snapshot.projectAcronym, o.snapshot.snapshotName,
                o.contentBytes, o.hasContent, o.sha256, sections, outRefs, inRefs,
                outCount, inCount);
    }

    /**
     * Script fragments of an object (script blocks, BPMN-JSON script fields,
     * JS file raw text) — the same view as for the rules; entities unpacked,
     * duplicates (DOM block == JSON field) already removed.
     */
    @GET
    @Path("{id: .+}/scripts")
    @Transactional
    public List<ScriptDto> scripts(@PathParam("id") String id) {
        TwxObject o = TwxObject.findById(id);
        if (o == null) {
            throw new NotFoundException("Objekt nicht gefunden: " + id);
        }
        if (o.content == null) {
            return List.of();
        }
        return ScriptScanner.fragments(toParsed(o)).stream()
                .map(f -> new ScriptDto(f.description(), f.js(), f.lineOffset()))
                .toList();
    }

    /**
     * Diagram from the designer coordinates of the BPMN model JSON (B8);
     * 404-like empty when the object carries no coordinates.
     */
    @GET
    @Path("{id: .+}/diagram")
    @Transactional
    public DiagramDto diagram(@PathParam("id") String id) {
        TwxObject o = TwxObject.findById(id);
        if (o == null) {
            throw new NotFoundException("Objekt nicht gefunden: " + id);
        }
        Diagram.Model model = Diagram.of(o.content);
        if (model.isEmpty()) {
            return new DiagramDto(o.id, o.name, null, null, null, null, List.of(), List.of(), List.of());
        }
        double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
        for (Diagram.Node n : model.nodes()) {
            minX = Math.min(minX, n.x());
            minY = Math.min(minY, n.y());
            maxX = Math.max(maxX, n.x() + n.width());
            maxY = Math.max(maxY, n.y() + n.height());
        }
        // Lane bands often lie slightly outside the nodes (e.g. x=0, full
        // lane width) — include them in the canvas, otherwise lanes get cut off.
        for (Diagram.Lane l : model.lanes()) {
            minX = Math.min(minX, l.x());
            minY = Math.min(minY, l.y());
            maxX = Math.max(maxX, l.x() + l.width());
            maxY = Math.max(maxY, l.y() + l.height());
        }
        return new DiagramDto(o.id, o.name, minX - 20, minY - 20,
                maxX - minX + 40, maxY - minY + 40, model.nodes(), model.edges(), model.lanes());
    }

    private static ParsedObject toParsed(TwxObject o) {
        return new ParsedObject(o.id, o.prefix, o.typeName, o.name, o.description, o.sha256, o.content,
                o.subType);
    }

    /** Display type of a reference target: subtype (e.g. service/cshs) beats the family type. */
    private static String displayType(TwxObject o) {
        return o.subType != null && !o.subType.isBlank() ? o.subType : o.typeName;
    }

    /** Worldwide TWX object ID without the DB's snapshot prefix. */
    private static String globalId(TwxObject o) {
        int colon = o.id.indexOf(':');
        return colon >= 0 ? o.id.substring(colon + 1) : o.id;
    }
}

package io.twxav2.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;

/**
 * User-defined exclusion of an object from ALL rules of a snapshot
 * (e.g. test/template/deprecated services). The key is the TWX-internal
 * object ID ("<prefix>.<uuid>") — it stays identical across re-analyses and
 * renewed uploads; rows are taken over on replace-import.
 */
@Entity
// Deliberately without a unique constraint: the physical column is named "twxId" (without
// naming-strategy conversion); a constraint on "twx_id" fails in the DDL
// (MariaDB warning 1072 on every start). Deduplication happens application-side
// in ExclusionService/IngestService (known TWX-IDs per snapshot).
@Table(name = "object_exclusion",
       indexes = { @Index(name = "ix_object_exclusion_snapshot", columnList = "snapshot_id") })
public class ObjectExclusion extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @ManyToOne(optional = false)
    public Snapshot snapshot;

    public String twxId;   // "12.<uuid>" — without snapshot prefix
    public String name;    // object name for display
    /** null = manual (user exclude); otherwise AUTO_NAME / AUTO_TAG (rule-configured). */
    public String source;
    /** Why auto-excluded: the matched pattern word or tag. */
    public String reason;
}

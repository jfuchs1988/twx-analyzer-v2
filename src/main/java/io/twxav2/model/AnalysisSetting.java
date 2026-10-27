package io.twxav2.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;

/**
 * Stored rule settings as JSON: one global row (snapshotId null),
 * plus one optional row per snapshot. Content: rule overrides + thresholds.
 */
@Entity
@Table(name = "rule_settings",
       indexes = { @Index(name = "ix_rs_snapshot", columnList = "snapshot_id") })
public class AnalysisSetting extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    /** null = global default settings. */
    @Column(name = "snapshot_id")
    public Long snapshotId;

    @Column(length = 60000)
    public String payload;
}

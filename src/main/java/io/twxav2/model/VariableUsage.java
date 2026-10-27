package io.twxav2.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;

/**
 * Single usage of a BAW variable (tw.local.x, tw.processVariables.x, …) inside a
 * stored object content; scanned during ingest. Line + text are stored as well so
 * the usage view does not have to reload the content.
 */
@Entity
@Table(name = "variable_usage",
       indexes = { @Index(name = "ix_varusage_snap_var", columnList = "snapshot_id,variable") })
public class VariableUsage extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @ManyToOne(optional = false)
    public Snapshot snapshot;

    public String objectId;       // "16:433.<uuid>"
    public String objectName;
    public String prefix;         // object-type prefix (e.g. 433)
    public String scope;          // local | processVariables | variables | parameters | inputs | outputs
    public String variable;       // variable name without path
    public boolean assign;        // true = assignment, false = read access
    public int line;              // 1-based line number
    @Column(length = 400)
    public String lineText;       // line (trimmed, truncated)
}

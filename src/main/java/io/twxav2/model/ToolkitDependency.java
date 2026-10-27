package io.twxav2.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;

/** Edge snapshot -> toolkit snapshot (from the <dependencies> of package.xml). */
@Entity
@Table(name = "toolkit_dependency")
public class ToolkitDependency extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @ManyToOne(optional = false)
    public Snapshot snapshot;

    @ManyToOne
    public ToolkitSnapshot toolkit;   // null if the ZIP was not embedded in the TWX

    public String declaredName;       // project name from the dependency
    public String declaredAcronym;
    public String declaredVersion;    // declared snapshot version
    public String toolkitId;          // "2064.<uuid>"
    public int rank;
}

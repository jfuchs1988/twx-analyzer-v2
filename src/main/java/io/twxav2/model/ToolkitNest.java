package io.twxav2.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;

/**
 * Declared dependency OF ONE toolkit on another toolkit
 * ( nested <dependencies> from the package.xml of the toolkit ZIP).
 * Holds globally like ToolkitSnapshot; childToolkitId can be "2064.&lt;uuid&gt;"
 * even if the child ZIP was never embedded (then without inventory entry).
 */
@Entity
@Table(name = "toolkit_nest",
       indexes = { @Index(name = "ix_toolkit_nest_parent", columnList = "parent_id") })
public class ToolkitNest extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @ManyToOne(optional = false)
    public ToolkitSnapshot parent;

    public String childToolkitId;     // "2064.<uuid>" per declaration
    public String childAcronym;
    public String childName;
    public String childVersion;       // declared snapshot version
    public int rank;
}

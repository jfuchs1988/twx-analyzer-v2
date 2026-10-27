package io.twxav2.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;

/** One entry from objects/&lt;praefix&gt;.&lt;uuid&gt;.xml (incl. optional payload). */
@Entity
@Table(name = "twx_object",
       indexes = { @Index(name = "ix_obj_snapshot", columnList = "snapshot_id"),
                   @Index(name = "ix_obj_prefix", columnList = "prefix") })
public class TwxObject extends PanacheEntityBase {

    @Id
    @Column(length = 64)
    public String id;               // "1.<uuid>" as in the TWX

    @ManyToOne(optional = false)
    public Snapshot snapshot;

    public String prefix;           // "1", "25", "6023", ...
    public String typeName;         // process, bpd, eSArtifact, ...
    public String subType;          // display sub-type (service, cshs, subBpd, ...), null = none
    public String name;             // from the name attribute of the object root
    @Column(length = 1000)
    public String description;      // description attribute, if present
    @Column(name = "xml_hash", length = 64)
    public String sha256;
    public int contentBytes;
    public boolean hasContent;

    @Lob
    @Basic(fetch = FetchType.LAZY)
    public String content;          // object root XML (possibly + payload), size-limited
}

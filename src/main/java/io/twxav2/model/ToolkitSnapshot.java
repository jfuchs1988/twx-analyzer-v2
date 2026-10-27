package io.twxav2.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import java.time.Instant;

/** Globally unique toolkit snapshot (dedup due to shared content: process apps often share toolkits). */
@Entity
@Table(name = "toolkit_snapshot",
       indexes = { @Index(name = "ix_toolkit_sha", columnList = "sha256") })
public class ToolkitSnapshot extends PanacheEntityBase {

    @Id
    @Column(length = 64)
    public String id;               // "2064.<uuid>"

    public String projectName;
    public String projectAcronym;
    public String version;          // snapshot name, e.g. v1.0.2
    @Column(length = 64)
    public String sha256;           // hash of the embedded ZIP file
    public int objectCount;
    public Instant firstSeen = Instant.now();
}

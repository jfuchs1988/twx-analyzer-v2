package io.twxav2.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import java.time.Instant;

/** One uploaded TWX export (= snapshot export from the Process Designer). */
@Entity
@Table(name = "snapshot")
public class Snapshot extends PanacheEntityBase {

    public enum Status { UPLOADED, PARSED, READY, ERROR }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    public String filename;
    public long sizeBytes;

    // target package from META-INF/package.xml
    public String projectName;
    public String projectAcronym;   // shortName of the imported project
    @Column(length = 4000)
    public String projectDescription;
    public String branchName;
    public String snapshotName;     // e.g. v2.5.5
    @Column(length = 4000)
    public String snapshotDescription;
    public String exportTime;       // originalCreationDate (ISO string as in the TWX)
    public String buildVersion;     // e.g. 8.6.9
    @Column(length = 4000)
    public String buildDescription;
    public Boolean isToolkit;       // manifest attribute isToolkit (null = unknown/legacy inventory)

    public int objectCount;
    public int toolkitCount;
    public int findingCount;
    public long scriptLines;      // total JavaScript lines (services/processes/coach views), dashboard metric

    @Enumerated(EnumType.STRING)
    public Status status = Status.UPLOADED;
    @Column(length = 2000)
    public String statusMessage;

    public Instant uploadedAt = Instant.now();
}

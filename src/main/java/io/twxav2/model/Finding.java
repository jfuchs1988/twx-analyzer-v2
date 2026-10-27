package io.twxav2.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;

/** Result of an analysis rule hit. */
@Entity
@Table(name = "finding",
       indexes = { @Index(name = "ix_find_snapshot", columnList = "snapshot_id"),
                   @Index(name = "ix_find_rule", columnList = "ruleId") })
public class Finding extends PanacheEntityBase {

    /** Severity; order = order in the ranking. */
    public enum Severity { CRITICAL, MAJOR, MINOR, INFO }

    /** Team status per finding (B5): accepted findings stay out of health/gate/metrics. */
    public enum Status { OPEN, RESOLVED, ACCEPTED }

    /** Score weight per severity (rating model, requirements section Bewertung). */
    public static int weight(Severity s) {
        return switch (s) {
            case CRITICAL -> 100;
            case MAJOR -> 40;
            case MINOR -> 10;
            case INFO -> 2;
        };
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @ManyToOne(optional = false)
    public Snapshot snapshot;

    public String ruleId;             // "R-JS-022 Titel" (prefix search in the UI)

    @Enumerated(EnumType.STRING)
    public Severity severity;

    public String category;           // rule category (catalog)

    public Integer score;             // weight x impact, computed at analysis time

    public boolean needsReview;       // heuristic hit: "please review"

    @Column(name = "object_id", length = 64)
    public String objectId;
    public String objectName;

    @Column(name = "location", length = 300)
    public String location;           // place within the object (step, property, script location)

    @Column(name = "line_no")
    public Integer lineNo;

    @Column(length = 1000)
    public String snippet;            // hit as a code excerpt

    @Column(length = 600)
    public String message;

    /** English message (i18n); null = UI shows the German one. */
    @Column(name = "message_en", length = 600)
    public String messageEn;

    /** Team status (B5); null = open (legacy data without own row). */
    @Enumerated(EnumType.STRING)
    @Column(name = "finding_status", length = 16)
    public Status status;

    @Column(name = "status_comment", length = 1000)
    public String statusComment;

    @Column(name = "status_at")
    public java.time.Instant statusAt;
}

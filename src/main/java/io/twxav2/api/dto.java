package io.twxav2.api;

import io.twxav2.model.Finding;
import io.twxav2.model.Snapshot;
import io.twxav2.model.ToolkitDependency;
import io.twxav2.model.ToolkitSnapshot;
import io.twxav2.model.TwxObject;

import java.util.List;

/** JSON DTOs (records) for the API. */
public final class dto {

    private dto() { }

    public record SnapshotDto(Long id, String filename, long sizeBytes,
                              String projectName, String projectAcronym, String branchName,
                              String snapshotName, String snapshotDescription, String exportTime,
                              String buildVersion, int objectCount, int toolkitCount, int findingCount,
                              Integer healthScore, String status, String statusMessage,
                              String uploadedAt, Boolean isToolkit) {

        /** Health score (0..100): rated findings per object cause an exponential drop. */
        public static Integer healthScore(int totalScore, int objectCount) {
            double size = Math.max(50, objectCount);
            double density = totalScore / size;
            return (int) Math.max(0, Math.round(100 * Math.exp(-density / 40.0)));
        }

        public static SnapshotDto of(Snapshot s) {
            return of(s, null);
        }

        public static SnapshotDto of(Snapshot s, Integer scoreTotal) {
            return new SnapshotDto(s.id, s.filename, s.sizeBytes, s.projectName, s.projectAcronym,
                    s.branchName, s.snapshotName, s.snapshotDescription, s.exportTime, s.buildVersion,
                    s.objectCount, s.toolkitCount, s.findingCount,
                    scoreTotal == null ? null : healthScore(scoreTotal, s.objectCount),
                    s.status.name(), s.statusMessage, String.valueOf(s.uploadedAt), s.isToolkit);
        }
    }

    /** B10: one bar segment — a rule with its hit count in the snapshot. */
    public record RuleBarDto(String rule, String severity, long count) { }

    public record FindingDto(Long id, String ruleId, String severity, String category,
                             Integer score, boolean needsReview,
                             String objectId, String objectName, String location,
                             Integer line, String snippet, String message, String messageEn,
                             String status, String statusComment, String statusAt) {

        public static FindingDto of(Finding f) {
            Finding.Status st = f.status == null ? Finding.Status.OPEN : f.status;
            return new FindingDto(f.id, f.ruleId, f.severity.name(), f.category, f.score,
                    f.needsReview, f.objectId, f.objectName, f.location, f.lineNo,
                    f.snippet, f.message, f.messageEn, st.name(), f.statusComment,
                    f.statusAt == null ? null : String.valueOf(f.statusAt));
        }
    }

    public record ObjectDto(String id, String prefix, String typeName, String subType, String name,
                            String description, int contentBytes, boolean hasContent, boolean excluded) {

        public static ObjectDto of(TwxObject o) {
            return of(o, false);
        }

        public static ObjectDto of(TwxObject o, boolean excluded) {
            return new ObjectDto(o.id, o.prefix, o.typeName, o.subType, o.name, o.description,
                    o.contentBytes, o.hasContent, excluded);
        }
    }

    /** An object exclusion from the API's perspective: manual or automatic (name/tag). */
    public record ExclusionDto(String twxId, String name, String typeName,
                               String source, String reason) { }

    /** Body for PUT /api/snapshots/{id}/exclusions: DB ID "<snapshot>:<praefix>.<uuid>". */
    public record ExclusionReq(String objectId) { }

    public record ObjectContentDto(String id, String name, String typeName, String subType, String content) { }

    public record ToolkitDto(String toolkitId, String declaredName, String declaredAcronym,
                             String declaredVersion, String actualVersion, String sha256,
                             Integer objectCount, boolean embedded, boolean declared,
                             String usage, List<String> usageEvidence) {

        public static ToolkitDto of(ToolkitDependency d, String usage, List<String> usageEvidence) {
            ToolkitSnapshot t = d.toolkit;
            boolean declared = d.declaredName != null || d.declaredVersion != null;
            return new ToolkitDto(d.toolkitId,
                    declared ? d.declaredName : (t != null ? t.projectName : null),
                    declared ? d.declaredAcronym : (t != null ? t.projectAcronym : null),
                    d.declaredVersion,
                    t != null ? t.version : null, t != null ? t.sha256 : null,
                    t != null ? t.objectCount : null, t != null, declared,
                    usage, usageEvidence);
        }
    }

    /** prefix group of the object list; subType only set when the group is split by subtype. */
    public record TypeCountDto(String prefix, String subType, String name, long count) { }

    /** A search hit: object plus its matches (max. 3 per object). */
    public record SearchHit(String objectId, Long snapshotId, String snapshotLabel,
                            String typeName, String name, Boolean isToolkit,
                            List<SearchMatch> matches) { }

    /** One match: scope, location (script block or similar), line, snippet. */
    public record SearchMatch(String scope, String where, int line, String snippet) { }

    /** Search response with abort info (budget/limit) and an error field for regex syntax. */
    public record SearchResponse(List<SearchHit> hits, boolean truncated, long elapsedMs,
                                 String error) { }

    /** Global toolkit inventory entry (dedup store, survives snapshot deletions);
     *  inCycle = per the ToolkitNest graph the node lies on a dependency cycle (B4.1). */
    public record ToolkitInventoryDto(String id, String projectAcronym, String projectName,
                                      String version, String sha256, int objectCount,
                                      long usedBy, String firstSeen,
                                      List<ToolkitNestedDto> nested, boolean inCycle) { }

    /** Nested dependency of a toolkit; actual fields only when the child
     *  ZIP is known in the inventory (version from the real ZIP).
     *  childVersionLatest = highest version of the child acronym known in the global
     *  inventory; childOutdated = embedded (known) or declared
     *  (unknown) version older than that. */
    public record ToolkitNestedDto(String childToolkitId, String childAcronym, String childName,
                                   String childVersion, String childVersionActual,
                                   String childSha256, boolean known,
                                   String childVersionLatest, boolean childOutdated,
                                   boolean childInCycle) { }

    /** Variable catalog: aggregate per snapshot+scope+variable. */
    public record VariableRow(String snapshotId, String snapshotLabel, String scope,
                              String variable, long assigns, long reads, long objects) { }

    /** A single usage of a variable (line-exact). */
    public record VariableUsageRow(String objectId, String objectName, String prefix,
                                   String scope, int line, boolean assign, String lineText) { }

    /** BPM access credentials without the password (only the passwordSet flag, ADR-010). */
    public record BpmSettingsDto(String baseUrl, String username, boolean passwordSet,
            String caFile) { }

    /** Toolkit dependency graph of a snapshot: declarative + nested edges,
     *  known toolkits (inventory) as well as children that were only declared but never
     *  embedded (known=false, version from the declaration alone). */
    public record ToolkitGraphNode(String toolkitId, String acronym, String name, String version,
                                   String sha256, Integer objectCount, int depth,
                                   String declaredVersion, boolean known) { }

    /** Edge parentId -> childId (parentId "" = app/manifest). */
    public record ToolkitGraphEdge(String parentId, String childId, String version) { }

    /** One usage of a toolkit in the graph (who points at which version). */
    public record ToolkitGraphUse(String toolkitId, String version, String sha256,
                                  boolean known, List<String> usedBy) { }

    /** Version column: same toolkit acronym, but different versions in the graph. */
    public record ToolkitGraphConflict(String acronym, String name, boolean sameContent,
                                       List<ToolkitGraphUse> uses) { }

    public record ToolkitGraphDto(List<ToolkitGraphNode> nodes, List<ToolkitGraphEdge> edges,
                                  List<ToolkitGraphConflict> conflicts, List<String> cycleNodes) { }

    /** Toolkit version conflicts per app (overview in the toolkit main menu). */
    public record AppToolkitConflicts(Long snapshotId, String app, String version,
                                      List<ToolkitGraphConflict> conflicts) { }

    /** Quality gate (B9): finding counts without accepted ones, health, reasons. */
    public record GateDto(Long snapshotId, boolean passed, int exitCode,
                          int critical, int major, int health, int minHealth,
                          int maxCritical, int maxMajor, int accepted, List<String> reasons) { }

    /** Task row (B5.2): todo findings per object with status roles. */
    public record TaskRow(String objectId, String objectName,
                          int open, int resolved, int accepted, int total, String rules) { }

    /** Version comparison (B6): two snapshots with object and rule differences. */
    public record CompareDto(SnapshotDto base, SnapshotDto target,
                             List<ObjectDiff> addedObjects, List<ObjectDiff> removedObjects,
                             List<ObjectDiff> changedObjects, List<RuleDelta> rules,
                             long baseFindings, long targetFindings,
                             int baseScore, int targetScore, int baseHealth, int targetHealth) { }

    /** Object difference (B6): an identity-equal object in both analyses or on one side only. */
    public record ObjectDiff(String id, String name, String typeName, String prefix) { }

    /** Finding trend per rule acronym (B6); numbers exclude accepted findings. */
    public record RuleDelta(String rule, long baseCount, long targetCount,
                            int baseScore, int targetScore) { }

    /** Dashboard tile per project (B5.3): health trend + open criticals;
     *  service/bpd counts and script lines always of the latest analysis. */
    public record DashboardProject(String key, String acronym, String name,
                                   List<DashboardPoint> trend,
                                   Long latestSnapshotId, String latestSnapshotName,
                                   Integer latestHealth, long openCritical, String lastImport,
                                   boolean toolkit,
                                   int serviceCount, int bpdCount, long scriptLines) { }

    /** One point of the health curve (per analysis snapshot, older first). */
    public record DashboardPoint(Long snapshotId, String snapshotName, Integer health,
                                 String uploadedAt) { }

    /** Structured object details (B7): tables per object type + references. */
    public record ObjectDetailDto(String id, String prefix, String typeName, String subType, String name,
                                  String description, Long snapshotId, String snapshotAcronym,
                                  String snapshotVersion, int contentBytes, boolean hasContent,
                                  String sha256,
                                  List<io.twxav2.analysis.details.ObjectDetails.Section> sections,
                                  List<RefRow> outRefs, List<RefRow> inRefs,
                                  int outRefCount, int inRefCount) { }

    /** Reference pointer (B7): object that points at another one (or is pointed at by it). */
    public record RefRow(String objectId, String objectName, String typeName) { }

    /** Script fragment of an object (script block/BPMN-JSON field/JS file) with origin line. */
    public record ScriptDto(String description, String js, int line) { }

    /** Process/BPD diagram (B8) from the designer coordinates of the model JSON. */
    public record DiagramDto(String objectId, String name,
                             Double minX, Double minY, Double width, Double height,
                             List<io.twxav2.analysis.diagram.Diagram.Node> nodes,
                             List<io.twxav2.analysis.diagram.Diagram.Edge> edges,
                             List<io.twxav2.analysis.diagram.Diagram.Lane> lanes) { }

    /** XML detail diff (B6 addendum): line diff of an object version base->target. */
    public record ContentDiffDto(String baseObjectId, String targetObjectId,
                                 String baseName, String targetName,
                                 int baseBytes, int targetBytes,
                                 int additions, int deletions,
                                 boolean truncated, String note,
                                 List<io.twxav2.analysis.diff.LineDiff.Line> lines) { }
}

package io.twxav2.service;

import io.twxav2.analysis.RuleEngine;
import io.twxav2.ingest.TwxParseResult;
import io.twxav2.ingest.TwxParser;
import io.twxav2.model.*;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

/** Upload -> parse -> persist -> analysis. */
@ApplicationScoped
public class IngestService {

    @Inject
    RuleEngine engine;

    @Inject
    io.twxav2.service.RuleSettingsService ruleSettings;

    @Inject
    ExclusionService autoExclusions;

    @Inject
    EntityManager em;

    @ConfigProperty(name = "twxav2.uploads.dir")
    String uploadsDir;

    /**
     * Compute auto-exclusions and store them as ObjectExclusion rows (source AUTO_NAME /
     * AUTO_TAG); excludedIds is extended, manual entries never lose
     * (purpose: a row does not exist twice per twxId).
     */
    private void applyAutoExclusions(Snapshot s, io.twxav2.analysis.RuleSettings eff,
                                     java.util.Set<String> excludedIds, TwxParseResult parsed) {
        List<String> patterns = autoExclusions.namePatterns(eff);
        List<String> tagList = autoExclusions.tags(eff);
        if (patterns.isEmpty() && tagList.isEmpty()) {
            return;
        }
        for (var po : parsed.objects()) {
            String[] m = autoExclusions.match(po.name(), po.tags(), patterns, tagList);
            if (m != null && excludedIds.add(po.id())) {
                persistAutoExclusion(s, po.id(), po.name(), m);
            }
        }
        // Toolkit objects: never tagged in the export, only name patterns can match
        for (var pt : parsed.toolkits()) {
            for (var to : pt.objects()) {
                String[] m = autoExclusions.match(to.name(), List.of(), patterns, tagList);
                if (m != null && excludedIds.add(to.id())) {
                    persistAutoExclusion(s, to.id(), to.name(), m);
                }
            }
        }
    }

    private void persistAutoExclusion(Snapshot s, String twxId, String name, String[] match) {
        ObjectExclusion x = new ObjectExclusion();
        x.snapshot = s;
        x.twxId = twxId;
        x.name = name;
        x.source = match[0];
        x.reason = match[1];
        x.persist();
    }

    /** Phase feedback to the calling queue task (may be null). */
    @Transactional
    public Snapshot ingest(String filename, long size, java.nio.file.Path tmp,
                           java.util.function.Consumer<IngestQueue.Phase> phase) throws IOException {
        Path dir = Path.of(uploadsDir);
        Files.createDirectories(dir);
        Path file = dir.resolve(System.currentTimeMillis() + "_" + sanitize(filename));
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ex) {
            Files.copy(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }

        Snapshot s = new Snapshot();
        s.filename = filename;
        s.sizeBytes = size;
        s.persist();
        Snapshot replaced = null;
        try {
            if (phase != null) {
                phase.accept(IngestQueue.Phase.PARSE);
            }
            TwxParseResult parsed = TwxParser.parse(file);
            var m = parsed.manifest();
            s.projectName = m.projectName();
            s.projectAcronym = m.projectShortName();
            s.projectDescription = TwxParser.truncate(m.projectDescription(), 4000);
            s.branchName = m.branchName();
            s.snapshotName = m.snapshotName();
            s.snapshotDescription = TwxParser.truncate(m.snapshotDescription(), 4000);
            s.exportTime = m.snapshotDate();
            s.buildVersion = m.buildVersion();
            s.buildDescription = TwxParser.truncate(m.buildDescription(), 4000);
            s.isToolkit = m.toolkit();
            s.status = Snapshot.Status.PARSED;

            // Backstop (e.g. renamed file or BPM import): same project
            // + same version already READY -> replace-import: the old analysis is
            // deleted only after successful intake (harm only if everything works).
            if (s.projectAcronym != null && s.snapshotName != null) {
                replaced = Snapshot.find(
                        "id <> ?1 and status = ?2 and lower(projectAcronym) = ?3 and lower(snapshotName) = ?4",
                        s.id, Snapshot.Status.READY,
                        s.projectAcronym.toLowerCase(), s.snapshotName.toLowerCase())
                        .firstResult();
                if (replaced != null) {
                    // Take over the predecessor's exclude list: re-analyze/re-upload
                    // of a snapshot keeps the user exclusions. Auto-exclusions
                    // are not taken over — they belong to the configuration and
                    // are recomputed below on each run.
                    for (ObjectExclusion x : ObjectExclusion.<ObjectExclusion>list("snapshot.id", replaced.id)) {
                        if (x.source != null && !ExclusionService.SRC_MANUAL.equals(x.source)) {
                            continue;
                        }
                        ObjectExclusion n = new ObjectExclusion();
                        n.snapshot = s;
                        n.twxId = x.twxId;
                        n.name = x.name;
                        n.persist();
                    }
                    // Attach the predecessor's rule settings to the successor
                    // (snapshot ID changes on re-analysis/re-upload); an already
                    // set own row of the new snapshot wins.
                    if (AnalysisSetting.find("snapshotId", s.id).count() == 0) {
                        for (AnalysisSetting st : AnalysisSetting.<AnalysisSetting>list("snapshotId", replaced.id)) {
                            st.snapshotId = s.id;
                        }
                    }
                }
            }

            java.util.List<VariableUsage> varRows = new java.util.ArrayList<>();
            for (var po : parsed.objects()) {
                TwxObject o = new TwxObject();
                o.id = s.id + ":" + po.id();
                o.snapshot = s;
                o.prefix = po.prefix();
                o.typeName = po.typeName();
                o.subType = po.subType();
                o.name = po.name();
                o.description = po.description() == null ? null : TwxParser.truncate(po.description(), 1000);
                o.sha256 = po.sha256();
                o.hasContent = po.content() != null;
                o.contentBytes = po.content() == null ? 0 : po.content().length();
                o.content = po.content();
                em.persist(o);
                VariableScanner.scan(s, o.id, o.name, o.prefix, o.content, varRows);
            }
            for (VariableUsage v : varRows) {
                em.persist(v);
            }
            s.objectCount = parsed.objects().size();

            java.util.Set<String> embeddedIds = new java.util.LinkedHashSet<>();
            for (var pt : parsed.toolkits()) {
                ToolkitSnapshot ts = ToolkitSnapshot.findById(pt.toolkitId());
                if (ts == null) {
                    ts = new ToolkitSnapshot();
                    ts.id = pt.toolkitId();
                    ts.projectName = pt.projectName();
                    ts.projectAcronym = pt.projectShortName();
                    ts.version = pt.version();
                    ts.sha256 = pt.sha256();
                    ts.objectCount = pt.objectCount();
                    ts.persist();
                }
                // Nested toolkit dependencies (package.xml of the ZIP) stored once;
                // if the set is still missing (inventory predates the feature), backfill
                // on the next read.
                if (!pt.nested().isEmpty() && ToolkitNest.count("parent.id", ts.id) == 0) {
                    for (var nd : pt.nested()) {
                        if (nd.toolkitId() == null) {
                            continue; // child ZIP missing from the export: no row, the rule set reports it
                        }
                        ToolkitNest nest = new ToolkitNest();
                        nest.parent = ts;
                        nest.childToolkitId = nd.toolkitId();
                        nest.childAcronym = nd.projectShortName();
                        nest.childName = nd.projectName();
                        nest.childVersion = nd.snapshotName();
                        nest.rank = nd.rank();
                        nest.persist();
                    }
                }
                embeddedIds.add(pt.toolkitId());
            }
            java.util.Set<String> listed = new java.util.LinkedHashSet<>();
            for (var d : m.dependencies()) {
                if (d.toolkitId() == null) {
                    // declared but ZIP missing from the export: no DB row possible
                    // (purpose primary key); appears as a "missing:" entry
                    // in the list of declared but missing toolkits
                    listed.add("missing:" + d.projectShortName());
                    continue;
                }
                ToolkitDependency dep = new ToolkitDependency();
                dep.snapshot = s;
                dep.toolkit = ToolkitSnapshot.findById(d.toolkitId());
                dep.declaredName = d.projectName();
                dep.declaredAcronym = d.projectShortName();
                dep.declaredVersion = d.snapshotName();
                dep.toolkitId = d.toolkitId();
                dep.rank = d.rank();
                dep.persist();
                listed.add(d.toolkitId());
            }
            int declaredCount = listed.size();
            // Embedded toolkit ZIPs not declared in the manifest are stored only as
            // an optional row (can-be inventory of earlier project states); they do
            // not count into toolkitCount and are not analyzed by the rules.
            for (String tid : embeddedIds) {
                if (listed.add(tid)) {
                    ToolkitDependency dep = new ToolkitDependency();
                    dep.snapshot = s;
                    dep.toolkit = ToolkitSnapshot.findById(tid);
                    dep.toolkitId = tid;
                    dep.rank = 10_000;
                    dep.persist();
                }
            }
            s.toolkitCount = declaredCount;

            if (phase != null) {
                phase.accept(IngestQueue.Phase.ANALYZE);
            }
            // Auto-exclusion "do not check": name patterns (project and toolkit
            // objects, e.g. _deprecated) plus Process Center tags (project
            // objects only — metadata.xml lists no toolkit objects). Effective globally
            // (properties/env) and per app (rule settings). Stored as
            // ObjectExclusion rows with source: the exclude filter below and the
            // UI tab "Excluded" use the same inventory.
            io.twxav2.analysis.RuleSettings eff = ruleSettings.effective(s.id);
            java.util.Set<String> excludedIds = new java.util.HashSet<>();
            for (ObjectExclusion x : ObjectExclusion.<ObjectExclusion>list("snapshot.id", s.id)) {
                excludedIds.add(x.twxId);
            }
            applyAutoExclusions(s, eff, excludedIds, parsed);
            List<RuleEngine.RuleFinding> findings = excludedIds.isEmpty()
                    ? engine.run(parsed, eff)
                    : engine.run(parsed, eff).stream()
                            .filter(f -> !excludedIds.contains(f.hit().objectId()))
                            .toList();
            for (var f : findings) {
                Finding fin = new Finding();
                fin.snapshot = s;
                fin.ruleId = f.ruleId() + " " + f.ruleTitle();
                fin.severity = f.severity();
                fin.category = f.category();
                fin.score = f.score();
                fin.needsReview = f.hit().needsReview();
                fin.objectId = f.hit().objectId();
                fin.objectName = f.hit().objectName();
                fin.location = TwxParser.truncate(f.hit().location(), 300);
                fin.lineNo = f.hit().line();
                fin.snippet = TwxParser.truncate(f.hit().snippet(), 1000);
                fin.message = TwxParser.truncate(f.hit().message(), 600);
                fin.messageEn = f.hit().messageEn() == null ? null
                        : TwxParser.truncate(f.hit().messageEn(), 600);
                fin.persist();
            }
            s.findingCount = findings.size();
            // Dashboard metric (B5.3): JavaScript lines of the app; computed after
            // the rules so it reuses nothing from their scratch (single fresh pass).
            s.scriptLines = io.twxav2.analysis.RuleEngine.scriptMetrics(parsed).scriptLines();
            s.status = Snapshot.Status.READY;
        } catch (Exception | Error ex) {
            // Catch Error too (e.g. StackOverflowError): otherwise the task
            // would remain non-terminal in the queue
            s.status = Snapshot.Status.ERROR;
            s.statusMessage = TwxParser.truncate(ex.getClass().getSimpleName() + ": " + ex.getMessage(), 500);
        }
        if (s.status == Snapshot.Status.READY) {
            if (replaced != null && Snapshot.findById(replaced.id) != null) {
                // Replace-import succeeded: cascade the old analysis (findings/triage
                // dropped; global toolkit dedup remains).
                deleteDataOf(replaced.id);
            }
            // Archive cleanup: keep only the file just stored, delete old copies
            // of same-named imports (deleteUploadFile distinguishes only by
            // suffix and would otherwise delete the new file too).
            try (var files = Files.list(dir)) {
                String suffix = "_" + sanitize(filename);
                files.filter(p -> !p.equals(file) && p.getFileName().toString().endsWith(suffix))
                        .forEach(p -> {
                            try {
                                Files.deleteIfExists(p);
                            } catch (IOException ignored) {
                            }
                        });
            } catch (IOException ignored) {
            }
        }
        s.persist();
        return s;
    }

    /**
     * Retrospective variable scan over all existing snapshots (for versions that
     * have not filled variable_usage yet). Returns the row count.
     */
    @Transactional
    public int backfillVariableUsage() {
        VariableUsage.deleteAll();
        int total = 0;
        for (Snapshot s : Snapshot.<Snapshot>listAll()) {
            List<TwxObject> objs = TwxObject.find("snapshot.id = ?1 and hasContent = true", s.id).list();
            List<VariableUsage> rows = new java.util.ArrayList<>();
            for (TwxObject o : objs) {
                VariableScanner.scan(s, o.id, o.name, o.prefix, o.content, rows);
            }
            for (VariableUsage v : rows) {
                em.persist(v);
            }
            em.flush();
            em.clear();
            total += rows.size();
        }
        return total;
    }

    @Transactional
    public boolean delete(Long id) {
        return delete(id, false);
    }

    /**
     * Deletes snapshot incl. cascade. keepUploadFile=true keeps the archive file
     * (replace-import: the same-named file is currently being re-read and would
     * be hit via suffix matching).
     */
    @Transactional
    public boolean delete(Long id, boolean keepUploadFile) {
        Snapshot s = Snapshot.findById(id);
        if (s == null) {
            return false;
        }
        deleteDataOf(s.id);
        if (!keepUploadFile) {
            deleteUploadFile(s);
        }
        return true;
    }

    /** DB cascade of a snapshot (without archive file) — also called from ingest(). */
    private void deleteDataOf(Long id) {
        Finding.delete("snapshot.id", id);
        TwxObject.delete("snapshot.id", id);
        ToolkitDependency.delete("snapshot.id", id);
        VariableUsage.delete("snapshot.id", id);
        AnalysisSetting.delete("snapshotId", id);
        ObjectExclusion.delete("snapshot.id", id);
        Snapshot.delete("id", id);
    }

    /**
     * Stored archive file of a snapshot (data/uploads, schema
     * &lt;timestamp&gt;_&lt;sanitized filename&gt;), newest first; null if none.
     * Basis for local re-analysis without a renewed file upload.
     */
    public Path findUpload(Snapshot s) {
        if (s == null || s.filename == null) {
            return null;
        }
        String suffix = "_" + sanitize(s.filename);
        try (var files = Files.list(Path.of(uploadsDir))) {
            return files.filter(p -> p.getFileName().toString().endsWith(suffix))
                    .max(java.util.Comparator.comparing(p -> p.getFileName().toString()))
                    .orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    private void deleteUploadFile(Snapshot s) {
        try (var files = Files.list(Path.of(uploadsDir))) {
            files.filter(p -> p.getFileName().toString().endsWith("_" + sanitize(s.filename)))
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException ignored) {
                        }
                    });
        } catch (IOException ignored) {
        }
    }

    private static String sanitize(String name) {
        return name == null ? "upload.twx" : name.replaceAll("[^A-Za-z0-9._ -]", "_");
    }
}

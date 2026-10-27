package io.twxav2.ingest;

import java.util.List;
import java.util.Map;

/** Result of TWX parsing (not in the DB yet). */
public record TwxParseResult(
        Manifest manifest,
        List<ParsedObject> objects,
        List<ParsedToolkit> toolkits,
        List<ParseError> errors) {

    public record Manifest(
            String projectName, String projectShortName, String projectDescription,
            String branchName,
            String snapshotName, String snapshotDescription, String snapshotDate,
            String buildVersion, String buildDescription,
            Boolean toolkit,
            List<Dependency> dependencies) { }

    public record Dependency(String projectId, String projectName, String projectShortName,
                             String toolkitId, String snapshotName, int rank) { }

    /** content == null means: too large or binary file.
     *  subType: display sub-type (process types by processType, BPDs by embedding), null = none.
     *  tags: Process Center tags from META-INF/metadata.xml (project objects only, never toolkit objects). */
    public record ParsedObject(String id, String prefix, String typeName,
                               String name, String description,
                               String sha256, String content, String subType, List<String> tags) {

        public ParsedObject(String id, String prefix, String typeName,
                            String name, String description, String sha256, String content) {
            this(id, prefix, typeName, name, description, sha256, content, null, List.of());
        }

        public ParsedObject(String id, String prefix, String typeName,
                            String name, String description, String sha256, String content, String subType) {
            this(id, prefix, typeName, name, description, sha256, content, subType, List.of());
        }
    }

    public record ParsedToolkit(String toolkitId, String projectName, String projectShortName,
                                String version, String sha256, long sizeBytes, int objectCount,
                                List<Dependency> nested, List<ToolkitObject> objects,
                                Map<String, String> jsFiles) { }

    /** Object entry from a toolkit ZIP's manifest (reference target, without full XML content);
     *  xml contains only safe metadata stubs for managed assets and environment-variable names. */
    public record ToolkitObject(String id, String name, String typeName, String xml) { }

    public record ParseError(String entry, String message) { }
}

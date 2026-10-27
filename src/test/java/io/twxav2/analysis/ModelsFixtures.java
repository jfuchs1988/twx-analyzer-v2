package io.twxav2.analysis;

import io.twxav2.ingest.TwxParseResult;
import io.twxav2.ingest.TwxParseResult.Dependency;
import io.twxav2.ingest.TwxParseResult.Manifest;
import io.twxav2.ingest.TwxParseResult.ParsedObject;
import io.twxav2.ingest.TwxParseResult.ParsedToolkit;

import java.util.List;

/**
 * shared synthetic fixtures for the analysis models (B2-B7): TwxParseResult
 * built directly, without a ZIP — sufficient for all models that only
 * read objects()/toolkits()/manifest(); fixtures use synthetic data only.
 */
public final class ModelsFixtures {

    private ModelsFixtures() { }

    /** UUID-like suffixes (36 hex characters with hyphens, as in the TWX). */
    public static String uuid(char c) {
        return "11111111-1111-4111-8111-111111" + c + "11111";
    }

    public static ParsedObject obj(String id, String name, String type, String content) {
        String prefix = id.substring(0, id.indexOf('.'));
        return new ParsedObject(id, prefix, type, name, "", "sha-" + id, content);
    }

    public static TwxParseResult result(List<ParsedObject> objects) {
        return result(objects, List.of(), List.of());
    }

    public static TwxParseResult result(List<ParsedObject> objects,
                                        List<ParsedToolkit> toolkits,
                                        List<Dependency> dependencies) {
        Manifest m = new Manifest("Demo App", "DEMO", "", "Main", "v1", "", "2026-01-01",
                "8.6.9", "FP25000", false, dependencies);
        return new TwxParseResult(m, objects, toolkits, List.of());
    }

    public static ParsedToolkit toolkit(String shortName, List<Dependency> nested) {
        return new ParsedToolkit("tk-" + shortName, "Toolkit " + shortName, shortName,
                "1.0.0", "sha-" + shortName, 1000, 0, nested, List.of(), java.util.Map.of());
    }

    public static Dependency dep(String shortName) {
        return new Dependency("p-" + shortName, "Projekt " + shortName, shortName,
                "tk-" + shortName, "1.0.0", 1);
    }
}

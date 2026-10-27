package io.twxav2.ingest;

import java.util.Map;

/** Prefix -> type NAME of the TWX object class (verified against real exports). */
public final class TypeRegistry {

    private static final Map<String, String> TYPES = Map.ofEntries(
            Map.entry("1", "process"),
            Map.entry("4", "underCoverAgent"),
            Map.entry("12", "twClass"),
            Map.entry("21", "epv"),
            Map.entry("24", "participant"),
            Map.entry("25", "bpd"),
            Map.entry("61", "managedAsset"),
            Map.entry("62", "environmentVariableSet"),
            Map.entry("63", "projectDefaults"),
            Map.entry("64", "coachView"),
            Map.entry("72", "theme"),
            Map.entry("2051", "restService"),
            Map.entry("2053", "restServiceOperation"),
            Map.entry("2064", "snapshot"),
            Map.entry("2066", "project"),
            Map.entry("2069", "dependency"),
            Map.entry("2013", "event"),
            Map.entry("6023", "eSArtifact"));

    private TypeRegistry() { }

    public static String typeName(String prefix) {
        return TYPES.getOrDefault(prefix, "unknown (" + prefix + ")");
    }

    public static String prefixOf(String objectId) {
        int dot = objectId.indexOf('.');
        return dot > 0 ? objectId.substring(0, dot) : "?";
    }
}

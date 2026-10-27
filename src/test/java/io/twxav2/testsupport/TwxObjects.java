package io.twxav2.testsupport;

import io.twxav2.ingest.TwxParseResult;
import io.twxav2.ingest.TwxParseResult.ParsedObject;

import java.util.List;

/**
 * Synthetic mini-TWX objects for rule tests (tests do not use internal
 * TWX files). Mirrors the TwxParser result for single
 * entries — content == null as for binary/oversized objects.
 */
public final class TwxObjects {

    private TwxObjects() { }

    public static ParsedObject obj(String prefix, String id, String name, String content) {
        return new ParsedObject(id, prefix, "urn:test:type", name, null, "sha-" + id, content);
    }

    /** Process/BPD object with one <script> block (exactly the layout ScriptScanner sees). */
    public static ParsedObject scriptObj(String id, String script) {
        return obj("1", id, "Prozess " + id,
                "<process><script name=\"main\">" + script + "</script></process>");
    }

    public static TwxParseResult result(ParsedObject... objects) {
        return new TwxParseResult(null, List.of(objects), List.of(), List.of());
    }
}

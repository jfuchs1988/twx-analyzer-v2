package io.twxav2.analysis.coach;

import io.twxav2.ingest.TwxParseResult;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Model of the coach views (prefix 64) of a TWX: metadata, layout view refs,
 * inline scripts (JS/CSS), lifecycle JS, config options and binding types.
 * The TWX exports coach views as plain object XML (no files/ payloads);
 * the layout is entity-encoded XML in the <layout> element. Loads exactly
 * once per parse result (cache like Inventory/BpdModels).
 */
public final class CoachViews {

    /** An <inlineScript> block; typ = JS or CSS, code un-escaped from entity encoding. */
    public record Script(String name, String type, String code) { }

    /** A <configOption>: an option of the view settable from outside. */
    public record ConfigOption(String name, String label, String propertyType, boolean list) { }

    /** Parsed-out coach-view view of a 64.x object. */
    public record Cv(TwxParseResult.ParsedObject object, String id, String name,
                     int layoutItems, Set<String> viewRefs,
                     List<Script> scripts, Map<String, String> lifecycleJs,
                     int inlineJsChars, List<ConfigOption> configOptions,
                     int bindingTypes, boolean previewImage, boolean isMobileReady) {

        /** JS code of all sources: inline script blocks (type JS) + lifecycle functions. */
        public String allJs() {
            StringBuilder sb = new StringBuilder();
            for (var s : scripts) {
                if (!"CSS".equalsIgnoreCase(s.type())) {
                    sb.append(s.code()).append('\n');
                }
            }
            sb.append(String.join("\n", lifecycleJs.values()));
            return sb.toString();
        }
    }

    /** Lifecycle function fields of the view (view.xml schema). */
    private static final List<String> JS_FIELDS = List.of("loadJsFunction", "unloadJsFunction",
            "viewJsFunction", "changeJsFunction", "validateJsFunction", "collaborationJsFunction");

    private static final Pattern INLINE_SCRIPT =
            Pattern.compile("<inlineScript\\b[^>]*>(.*?)</inlineScript>", Pattern.DOTALL);
    private static final Pattern SCRIPT_TAG_ATTR = Pattern.compile("<inlineScript\\b[^>]*name=\"([^\"]*)\"");
    private static final Pattern CONFIG_OPTION =
            Pattern.compile("<configOption\\b[^>]*name=\"([^\"]*)\"[^>]*>(.*?)</configOption>", Pattern.DOTALL);
    private static final Pattern BINDING_TYPE = Pattern.compile("<bindingType\\b");

    private static final Map<Identity, CoachViews> CACHE = new WeakHashMap<>();

    private record Identity(TwxParseResult ref) {
        @Override
        public boolean equals(Object o) {
            return o instanceof Identity i && i.ref == ref;
        }

        @Override
        public int hashCode() {
            return System.identityHashCode(ref);
        }
    }

    public static synchronized CoachViews of(TwxParseResult parsed) {
        return CACHE.computeIfAbsent(new Identity(parsed), id -> new CoachViews(parsed));
    }

    private final List<Cv> views = new ArrayList<>();
    private final Map<String, Cv> byId = new LinkedHashMap<>();

    private CoachViews(TwxParseResult parsed) {
        for (var o : parsed.objects()) {
            if ("64".equals(o.prefix()) && o.content() != null) {
                Cv cv = parse(o);
                views.add(cv);
                byId.put(cv.id(), cv);
            }
        }
    }

    public List<Cv> views() {
        return views;
    }

    public Cv view(String id) {
        return byId.get(id);
    }

    // ---------- Parsing ----------

    private static Cv parse(TwxParseResult.ParsedObject o) {
        String raw = o.content();

        // layout (entity-encoded): number of layout items + referenced views
        int items = 0;
        Set<String> refs = new LinkedHashSet<>();
        String layout = group(raw, "<layout>(.*?)</layout>");
        if (layout != null) {
            String lx = unesc(layout);
            items = count(lx, "<ns2:layoutItem");
            Matcher m = Pattern.compile("<ns2:viewUUID>([^<]+)</ns2:viewUUID>").matcher(lx);
            while (m.find()) {
                refs.add(m.group(1).trim());
            }
        }

        // inline scripts (JS/CSS)
        List<Script> scripts = new ArrayList<>();
        int jsChars = 0;
        Matcher is = INLINE_SCRIPT.matcher(raw);
        while (is.find()) {
            String body = is.group(1);
            Matcher a = SCRIPT_TAG_ATTR.matcher(raw.substring(is.start(), is.end()));
            String name = a.find() ? a.group(1) : "Inline Javascript";
            String type = one(group(body, "<scriptType>(.*?)</scriptType>"), "JS");
            String code = unesc(one(group(body, "<scriptBlock>(.*?)</scriptBlock>"), ""));
            scripts.add(new Script(name, type, code));
            if (!"CSS".equalsIgnoreCase(type)) {
                jsChars += code.length();
            }
        }

        // lifecycle JS functions
        Map<String, String> lifecycle = new LinkedHashMap<>();
        for (String f : JS_FIELDS) {
            String v = group(raw, "<" + f + ">(.*?)</" + f + ">");
            if (v != null && !v.isBlank()) {
                String code = unesc(v);
                lifecycle.put(f, code);
                jsChars += code.length();
            }
        }

        // config options
        List<ConfigOption> options = new ArrayList<>();
        Matcher co = CONFIG_OPTION.matcher(raw);
        while (co.find()) {
            String body = co.group(2);
            String label = one(group(body, "<label>(.*?)</label>"), "").trim();
            String type = one(group(body, "<propertyType>(.*?)</propertyType>"), "");
            boolean list = "true".equals(one(group(body, "<isList>(.*?)</isList>"), "false"));
            options.add(new ConfigOption(co.group(1), label, type, list));
        }

        int bindings = count(BINDING_TYPE, raw);
        boolean preview = raw.contains("<previewImage>") || raw.contains("<previewAdvHtml>");
        boolean mobile = "true".equals(one(group(raw, "<isMobileReady>(.*?)</isMobileReady>"), "false"));
        return new Cv(o, o.id(), o.name(), items, refs, scripts, lifecycle, jsChars,
                options, bindings, preview, mobile);
    }

    // ---------- Helpers ----------

    /** First group of the pattern or null. */
    static String group(String s, String regex) {
        Matcher m = Pattern.compile(regex, Pattern.DOTALL).matcher(s);
        return m.find() ? m.group(1) : null;
    }

    private static String one(String v, String def) {
        return v == null ? def : v;
    }

    private static int count(String s, String needle) {
        int n = 0;
        for (int i = s.indexOf(needle); i >= 0; i = s.indexOf(needle, i + 1)) {
            n++;
        }
        return n;
    }

    private static int count(Pattern p, String s) {
        Matcher m = p.matcher(s);
        int n = 0;
        while (m.find()) {
            n++;
        }
        return n;
    }

    static String unesc(String s) {
        if (s == null || s.indexOf('&') < 0) {
            return s == null ? "" : s;
        }
        return s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
                .replace("&apos;", "'").replace("&#xD;", "\r").replace("&#13;", "\r")
                .replace("&#xA;", "\n").replace("&#10;", "\n").replace("&amp;", "&");
    }
}

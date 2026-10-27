package io.twxav2.api;

import io.quarkus.panache.common.Sort;
import io.twxav2.api.dto.SearchHit;
import io.twxav2.api.dto.SearchMatch;
import io.twxav2.api.dto.SearchResponse;
import io.twxav2.analysis.script.ScriptScanner;
import io.twxav2.ingest.TwxParseResult.ParsedObject;
import io.twxav2.model.TwxObject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.resteasy.reactive.RestQuery;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Search across object names, documentation, scripts and raw XML (scope toggles),
 * optionally as regex with a guarded subset syntax (no lookaround, no
 * backreferences, no repeated groups) and a time budget, so catastrophic
 * backtracking cannot hold up a request. Hits are opened in the UI's object
 * viewer (line + search-term highlight).
 */
@Path("/api/search")
@Produces(MediaType.APPLICATION_JSON)
public class SearchResource {

    /** Known scopes (order = output order per object). */
    private static final List<String> SCOPES = List.of("name", "docs", "scripts", "raw");
    private static final int MATCHES_PER_OBJECT = 3;
    private static final long DEFAULT_BUDGET_MS = 5_000;
    private static final long MAX_BUDGET_MS = 15_000;
    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 200;

    @GET
    @Transactional
    public Response search(@RestQuery String q,
                           @RestQuery Long snapshotId,
                           @RestQuery String prefixes,
                           @RestQuery String type,
                           @RestQuery String scope,
                           @RestQuery Boolean regex,
                           @RestQuery @DefaultValue("false") boolean caseSensitive,
                           @RestQuery Integer limit,
                           @RestQuery Integer budgetMs) {
        long t0 = System.currentTimeMillis();
        boolean asRegex = regex != null && regex;
        if (q == null || q.trim().length() < (asRegex ? 1 : 2)) {
            return Response.ok(new SearchResponse(List.of(), false, 0,
                    "Suchbegriff zu kurz (Regex ab 1, Text ab 2 Zeichen)")).build();
        }
        String needle = q.trim();
        Set<String> scopes = parseScope(scope);
        if (scopes == null) {
            return bad("Unbekannter Scope. Erlaubt: " + String.join(", ", SCOPES));
        }
        Pattern pattern;
        try {
            pattern = compile(needle, asRegex, caseSensitive);
        } catch (SearchSyntaxException e) {
            return bad(e.getMessage());
        } catch (PatternSyntaxException e) {
            return bad("Ungueltige Regex: " + e.getDescription());
        }
        int maxObjects = Math.min(limit == null || limit <= 0 ? DEFAULT_LIMIT : limit, MAX_LIMIT);
        long budget = Math.min(budgetMs == null || budgetMs <= 0 ? DEFAULT_BUDGET_MS : budgetMs,
                MAX_BUDGET_MS);
        long deadline = System.nanoTime() + budget * 1_000_000L;

        List<TwxObject> candidates = candidates(snapshotId, prefixes, type);
        boolean needsContent = scopes.contains("scripts") || scopes.contains("raw");

        List<SearchHit> hits = new ArrayList<>();
        boolean truncated = false;
        for (TwxObject o : candidates) {
            if (hits.size() >= maxObjects || System.nanoTime() > deadline) {
                truncated = true;
                break;
            }
            List<SearchMatch> matches = new ArrayList<>();
            try {
                if (scopes.contains("name")) {
                    collect(matches, "name", null, o.name, pattern, asRegex, caseSensitive, needle,
                            deadline, MATCHES_PER_OBJECT);
                }
                if (scopes.contains("docs")) {
                    collect(matches, "docs", null, o.description, pattern, asRegex, caseSensitive,
                            needle, deadline, MATCHES_PER_OBJECT);
                }
                if (needsContent && o.content != null) {
                    if (scopes.contains("scripts")) {
                        for (ScriptScanner.Fragment f : ScriptScanner.fragments(toParsed(o))) {
                            collect(matches, "scripts", f.description(), f.js(), pattern,
                                    asRegex, caseSensitive, needle, deadline, MATCHES_PER_OBJECT);
                            if (matches.size() >= MATCHES_PER_OBJECT) {
                                break;
                            }
                        }
                    }
                    if (matches.size() < MATCHES_PER_OBJECT && scopes.contains("raw")) {
                        collect(matches, "raw", null, o.content, pattern, asRegex, caseSensitive,
                                needle, deadline, MATCHES_PER_OBJECT);
                    }
                }
            } catch (BudgetHit e) {
                truncated = true; // budget reached: serve the hits found so far
            }
            if (!matches.isEmpty()) {
                hits.add(new SearchHit(o.id, o.snapshot.id, snapshotLabel(o), o.typeName,
                        o.name, o.snapshot.isToolkit, matches));
            }
        }
        return Response.ok(new SearchResponse(hits, truncated,
                System.currentTimeMillis() - t0, null)).build();
    }

    /** Candidates per snapshot/prefix filter (content is read LAZY object by object). */
    private static List<TwxObject> candidates(Long snapshotId, String prefixes, String type) {
        StringBuilder query = new StringBuilder();
        List<Object> params = new ArrayList<>();
        if (snapshotId != null) {
            query.append("snapshot.id = ?1");
            params.add(snapshotId);
        }
        if ("toolkit".equalsIgnoreCase(type)) {
            query.append(query.isEmpty() ? "" : " and ").append("snapshot.isToolkit = true");
        } else if ("app".equalsIgnoreCase(type)) {
            query.append(query.isEmpty() ? "" : " and ")
                    .append("(snapshot.isToolkit is null or snapshot.isToolkit = false)");
        }
        if (prefixes != null && !prefixes.isBlank()) {
            List<String> pf = Arrays.stream(prefixes.split("[,;]"))
                    .map(String::trim).filter(s -> !s.isEmpty()).toList();
            if (!pf.isEmpty()) {
                query.append(query.isEmpty() ? "" : " and ")
                        .append("prefix in ?").append(params.size() + 1);
                params.add(pf);
            }
        }
        return TwxObject.list(query.toString(), Sort.by("id").ascending(), params.toArray());
    }

    /** Collect one search scope (plain text or regex) into matches, at most cap hits. */
    private static void collect(List<SearchMatch> matches, String scope, String where,
                                String text, Pattern pattern, boolean asRegex,
                                boolean caseSensitive, String needle, long deadline, int cap) {
        if (text == null || text.isEmpty() || matches.size() >= cap) {
            return;
        }
        if (asRegex) {
            Matcher m = pattern.matcher(new Budgeted(text, deadline));
            while (matches.size() < cap && m.find()) {
                matches.add(new SearchMatch(scope, where, ScriptScanner.lineAt(text, m.start()),
                        ScriptScanner.snippetAt(text, m.start())));
            }
            return;
        }
        String hay = caseSensitive ? text : text.toLowerCase(Locale.ROOT);
        String nee = caseSensitive ? needle : needle.toLowerCase(Locale.ROOT);
        int from = 0;
        while (matches.size() < cap) {
            int at = hay.indexOf(nee, from);
            if (at < 0) {
                return;
            }
            matches.add(new SearchMatch(scope, where, ScriptScanner.lineAt(text, at),
                    ScriptScanner.snippetAt(text, at)));
            from = at + Math.max(1, nee.length());
        }
    }

    /** Build the regex; in subset mode apply the safety guard first. */
    private static Pattern compile(String needle, boolean asRegex, boolean caseSensitive) {
        int flags = caseSensitive ? 0 : Pattern.CASE_INSENSITIVE;
        if (!asRegex) {
            return Pattern.compile(Pattern.quote(needle), flags);
        }
        String problem = regexProblem(needle);
        if (problem != null) {
            throw new SearchSyntaxException(problem);
        }
        return Pattern.compile(needle, flags);
    }

    /**
     * Guarding the regex subset: lookaround and backreferences
     * rejected; groups that themselves contain quantifiers must not be
     * quantified again ((a+)+ and relatives). Inline flags have no
     * effect (the case is controlled via caseSensitive) and are therefore
     * disallowed except (?...: . Returns: error message or null = okay.
     */
    static String regexProblem(String src) {
        if (containsLookaround(src)) {
            return "Regex-Teilmenge: Lookaround (?= (?! (?< ... ) ist nicht erlaubt.";
        }
        if (Pattern.compile("(?<!\\\\)\\\\[1-9]").matcher(src).find()) {
            return "Regex-Teilmenge: Rueckwaertsreferenzen (\\1) sind nicht erlaubt.";
        }
        // Group/quantifier check with a bracket stack; char classes [...] ignored.
        // "sawQuant" = a quantifier appeared at the current level (deliberately
        // coarse: (a|b+)c+ is also rejected — safety over expressiveness).
        Deque<Boolean> stack = new ArrayDeque<>();
        boolean inClass = false;
        boolean sawQuant = false;
        for (int i = 0; i < src.length(); i++) {
            char c = src.charAt(i);
            if (c == '\\') {
                i++;
                continue;
            }
            if (inClass) {
                if (c == ']') {
                    inClass = false;
                }
                continue;
            }
            switch (c) {
                case '[' -> inClass = true;
                case '(' -> {
                    stack.push(sawQuant);
                    sawQuant = false;
                }
                case ')' -> {
                    boolean innerHadQuant = sawQuant;
                    boolean quantified = innerHadQuant && nextIsQuantifier(src, i + 1);
                    if (quantified && !stack.isEmpty()) {
                        return "Regex-Teilmenge: wiederholte Gruppen wie (a+)+ sind nicht erlaubt.";
                    }
                    sawQuant = (!stack.isEmpty() ? stack.pop() : false) || innerHadQuant || quantified;
                    if (quantified) {
                        i = skipQuantifier(src, i + 1);
                    }
                }
                case '+', '*' -> sawQuant = true;
                case '{' -> {
                    if (i + 1 < src.length() && Character.isDigit(src.charAt(i + 1))) {
                        sawQuant = true;
                    }
                }
                default -> {
                }
            }
        }
        if (!stack.isEmpty()) {
            return "Regex-Teilmenge: unausbalancierte Klammer.";
        }
        return null;
    }

    /** true if (?...) occurs that is not a plain non-capturing group (?:...). */
    private static boolean containsLookaround(String src) {
        for (int i = src.indexOf("(?"); i >= 0; i = src.indexOf("(?", i + 1)) {
            if (i + 2 >= src.length()) {
                return true;
            }
            char k = src.charAt(i + 2);
            if (k == ':') {
                continue; // plain group: allowed
            }
            if ("idmsuxU-".indexOf(k) >= 0) {
                return true; // inline flags: rejected (the case runs via caseSensitive)
            }
            return true; // (?= (?! (?<
        }
        return false;
    }

    private static boolean nextIsQuantifier(String s, int i) {
        if (i >= s.length()) {
            return false;
        }
        char c = s.charAt(i);
        if (c == '+' || c == '*' || c == '?') {
            return true;
        }
        return c == '{' && i + 1 < s.length() && Character.isDigit(s.charAt(i + 1));
    }

    private static int skipQuantifier(String s, int i) {
        if (i < s.length() && s.charAt(i) == '{') {
            int close = s.indexOf('}', i);
            return close < 0 ? i : close;
        }
        if (i < s.length() && (s.charAt(i) == '+' || s.charAt(i) == '*')) {
            i++;
        }
        return i;
    }

    /** Parse the scope list; null on an unknown entry. Default: name + raw. */
    private static Set<String> parseScope(String scope) {
        if (scope == null || scope.isBlank()) {
            return new LinkedHashSet<>(List.of("name", "raw"));
        }
        Set<String> out = new LinkedHashSet<>();
        for (String s : scope.split("[,;]")) {
            String t = s.trim().toLowerCase(Locale.ROOT);
            if (t.isEmpty()) {
                continue;
            }
            if ("content".equals(t)) {
                t = "raw"; // backward compatible: earlier meaning
            }
            if (!SCOPES.contains(t)) {
                return null;
            }
            out.add(t);
        }
        return out.isEmpty() ? null : out;
    }

    private static ParsedObject toParsed(TwxObject o) {
        return new ParsedObject(o.id, o.prefix, o.typeName, o.name, o.description, o.sha256, o.content);
    }

    /** Display name of the parent snapshot for hit rows. */
    private static String snapshotLabel(TwxObject o) {
        var s = o.snapshot;
        String acr = s.projectAcronym != null && !s.projectAcronym.isBlank()
                ? s.projectAcronym : s.projectName;
        String ver = s.snapshotName != null && !s.snapshotName.isBlank() ? " " + s.snapshotName : "";
        return (acr != null && !acr.isBlank() ? acr : s.filename) + ver;
    }

    private static Response bad(String message) {
        return Response.status(400).entity(new SearchResponse(List.of(), false, 0, message)).build();
    }

    /** Search term outside the allowed regex subset. */
    private static final class SearchSyntaxException extends RuntimeException {
        SearchSyntaxException(String message) {
            super(message);
        }
    }

    /** Time budget exhausted (thrown from the matcher's CharAt path). */
    private static final class BudgetHit extends RuntimeException {
        BudgetHit() {
            super(null, null, false, false);
        }
    }

    /**
     * CharSequence that checks the time budget on every character access: Java regex
     * has no abort parameter; the input wrapper stops the search even
     * on pathological backtracking.
     */
    private static final class Budgeted implements CharSequence {
        private final String s;
        private final long deadline;

        Budgeted(String s, long deadline) {
            this.s = s;
            this.deadline = deadline;
        }

        private void tick() {
            if (System.nanoTime() > deadline) {
                throw new BudgetHit();
            }
        }

        @Override
        public int length() {
            tick();
            return s.length();
        }

        @Override
        public char charAt(int index) {
            tick();
            return s.charAt(index);
        }

        @Override
        public CharSequence subSequence(int start, int end) {
            tick();
            return s.subSequence(start, end);
        }

        @Override
        public String toString() {
            return s;
        }
    }
}

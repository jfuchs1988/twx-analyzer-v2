package io.twxav2.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.twxav2.model.BpmSettings;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509ExtendedTrustManager;
import java.io.IOException;
import java.io.InputStream;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Read-only access to the BAW operations REST interface (/ops/std/bpm/...).
 * Flow (ADR-010): POST {base}/ops/system/login with Basic-Auth obtains the CSRF token
 * (the only POST, solely for token issuance), then GETs only with
 * BPMCSRFToken header + session cookie. Data paths are read-only exclusively.
 */
@ApplicationScoped
public class BpmClient {

    /** Credentials slice, loaded outside a DB transaction. */
    public record Creds(String baseUrl, String username, String password, String caFile) {
        public boolean complete() {
            return baseUrl != null && !baseUrl.isBlank()
                    && username != null && !username.isBlank()
                    && password != null && !password.isBlank();
        }
    }

    /** Business error message for the API (HTTP 4xx/5xx with IBM error text). */
    public static class BpmException extends RuntimeException {
        public BpmException(String message) { super(message); }
    }

    /**
     * Fallback CA when the settings UI sets no CA file (env BPM_CA_FILE).
     * As Optional: kleiner-rye throws SRCFG00040 on an empty string against a normal
     * String injection (empty default = not set).
     */
    @ConfigProperty(name = "twxav2.bpm.ca-file")
    java.util.Optional<String> caFile;

    @ConfigProperty(name = "twxav2.bpm.request-timeout-seconds", defaultValue = "60")
    int requestTimeoutSeconds;

    @ConfigProperty(name = "twxav2.bpm.export-timeout-seconds", defaultValue = "600")
    int exportTimeoutSeconds;

    private final ObjectMapper mapper = new ObjectMapper();
    private volatile HttpClient http;
    private volatile String loadedCaPath;
    private volatile String csrfToken;

    @jakarta.inject.Inject
    SecretsCrypto secrets;

    @Transactional
    public Creds creds() {
        BpmSettings s = BpmSettings.get();
        String ca = s.caFile != null && !s.caFile.isBlank() ? s.caFile : blankToNull(caFile.orElse(null));
        return new Creds(s.baseUrl, s.username, secrets.decrypt(s.password), ca);
    }

    private static String blankToNull(String v) {
        return v == null || v.isBlank() ? null : v;
    }

    // ---------------- public operations (read-only) ----------------

    /** Connection test: login + container count (BPM provides no total field, so
     fetch like listContainers with a larger page and count). */
    public String testConnection(Creds c) {
        JsonNode root = getJson(c, "/ops/std/bpm/containers?size=2000");
        return "OK – Container gesamt: " + root.path("containers").size();
    }

    public List<ContainerInfo> listContainers(Creds c, String search) {
        JsonNode root = getJson(c, "/ops/std/bpm/containers?size=2000");
        List<ContainerInfo> out = new ArrayList<>();
        String needle = search == null ? "" : search.toLowerCase();
        for (JsonNode n : root.path("containers")) {
            String acr = n.path("container").asText();
            String name = n.path("container_name").asText();
            if (!needle.isEmpty()
                    && !acr.toLowerCase().contains(needle)
                    && !name.toLowerCase().contains(needle)) {
                continue;
            }
            out.add(new ContainerInfo(acr, name, n.path("toolkit").asBoolean(false)));
        }
        out.sort(Comparator.comparing(ContainerInfo::acronym, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(ContainerInfo::name, String.CASE_INSENSITIVE_ORDER));
        return out;
    }

    public List<SnapshotInfo> listSnapshots(Creds c, String acronym) {
        JsonNode root = getJson(c, "/ops/std/bpm/containers/"
                + URLEncoder.encode(acronym, StandardCharsets.UTF_8) + "/versions?size=500");
        return filterVersions(root);
    }

    /**
     * Filter version entries: all named snapshots plus the TIP
     * (development state). The TIP is always shown — it is always the
     * latest state of the project, even if newer named snapshots
     * are missing. Only unnamed entries (name missing or = technical
     * version number) without TIP marking are not selectable states.
     */
    static List<SnapshotInfo> filterVersions(JsonNode root) {
        List<SnapshotInfo> out = new ArrayList<>();
        for (JsonNode n : root.path("versions")) {
            boolean tip = n.path("tip").asBoolean(false);
            SnapshotInfo info = new SnapshotInfo(
                    n.path("version").asText(),
                    n.path("version_name").asText(""),
                    n.path("creation_date").asText(""),
                    n.path("description").asText(""),
                    n.path("branch_name").asText(""),
                    n.path("active").asBoolean(false),
                    tip,
                    n.path("status").asText(""));
            if (!tip && (info.versionName().isBlank() || info.versionName().equals(info.version()))) {
                continue;
            }
            out.add(info);
        }
        return out;
    }

    /** Downloads the TWX export of a snapshot into dest. */
    public Exported exportSnapshot(Creds c, String acronym, String version, Path dest) {
        String path = "/ops/std/bpm/containers/"
                + URLEncoder.encode(acronym, StandardCharsets.UTF_8)
                + "/versions/" + URLEncoder.encode(version, StandardCharsets.UTF_8)
                + "/export?use_enhanced_filenames=false";
        HttpResponse<InputStream> resp = send(c, path, exportTimeoutSeconds,
                HttpResponse.BodyHandlers.ofInputStream(), true);
        if (resp.statusCode() != 200) {
            String body = "";
            try (InputStream in = resp.body()) {
                // foreign server can send arbitrarily large error bodies -> cap
                body = summarize(new String(in.readNBytes(65_536), StandardCharsets.UTF_8));
            } catch (IOException ignored) { }
            throw new BpmException("Export fehlgeschlagen (HTTP " + resp.statusCode() + "): " + body);
        }
        String filename = filenameFromDisposition(resp.headers().firstValue("content-disposition").orElse(""));
        // Download with size cap (protect the disk): 2 GB
        long limit = 2L * 1024 * 1024 * 1024;
        try (InputStream in = resp.body();
                var out = java.nio.file.Files.newOutputStream(dest,
                        java.nio.file.StandardOpenOption.CREATE,
                        java.nio.file.StandardOpenOption.TRUNCATE_EXISTING)) {
            byte[] buf = new byte[64 * 1024];
            long total = 0;
            int n;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > limit) {
                    throw new BpmException("Export zu gross (> 2 GB) — Abbruch");
                }
                out.write(buf, 0, n);
            }
        } catch (IOException e) {
            throw new BpmException("Download-Schreibfehler: " + e.getMessage());
        }
        return new Exported(filename, dest.toFile().length());
    }

    public record ContainerInfo(String acronym, String name, boolean toolkit) { }

    public record SnapshotInfo(String version, String versionName, String creationDate,
                               String description, String branchName, boolean active,
                               boolean tip, String status) { }

    public record Exported(String filename, long sizeBytes) { }

    // ---------------- HTTP infrastructure ----------------

    private JsonNode getJson(Creds c, String path) {
        HttpResponse<String> resp = send(c, path, requestTimeoutSeconds,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8), false);
        if (resp.statusCode() == 200) {
            try {
                return mapper.readTree(resp.body());
            } catch (IOException e) {
                throw new BpmException("Unerwartete Antwort (kein JSON): " + summarize(resp.body()));
            }
        }
        throw new BpmException("HTTP " + resp.statusCode() + ": " + summarize(resp.body()));
    }

    /** GET with CSRF token + session cookie; on 401/403 log in once again. */
    private <T> HttpResponse<T> send(Creds c, String path, int timeoutSeconds,
                                     HttpResponse.BodyHandler<T> handler, boolean stream) {
        HttpResponse<T> resp = doSend(c, path, timeoutSeconds, handler, stream);
        if (resp.statusCode() == 401 || resp.statusCode() == 403) {
            synchronized (this) {
                csrfToken = null; // session broken/expired -> renew
            }
            resp = doSend(c, path, timeoutSeconds, handler, stream);
        }
        return resp;
    }

    private <T> HttpResponse<T> doSend(Creds c, String path, int timeoutSeconds,
                                       HttpResponse.BodyHandler<T> handler, boolean stream) {
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(c.baseUrl + path))
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .header("Authorization", basic(c))
                    .header("Accept", stream ? "*/*" : "application/json")
                    .GET();
            String tok = token(c);
            if (tok != null) {
                b.header("BPMCSRFToken", tok);
            }
            return client(c).send(b.build(), handler);
        } catch (IOException e) {
            throw new BpmException("BPM nicht erreichbar: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BpmException("Download unterbrochen");
        } catch (IllegalArgumentException e) {
            // URI.create throws on a broken base URL — do not pass through as a 500
            throw new BpmException("Ungueltige Basis-URL: " + e.getMessage());
        }
    }

    /** fetches the CSRF token via POST /ops/system/login (Basic-Auth); only when needed. */
    private String token(Creds c) {
        String tok = csrfToken;
        if (tok != null) {
            return tok;
        }
        synchronized (this) {
            if (csrfToken != null) {
                return csrfToken;
            }
            try {
                HttpRequest req = HttpRequest.newBuilder(URI.create(c.baseUrl + "/ops/system/login"))
                        .timeout(Duration.ofSeconds(requestTimeoutSeconds))
                        .header("Authorization", basic(c))
                        .header("Content-Type", "application/json")
                        .header("Accept", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("{}"))
                        .build();
                HttpResponse<String> resp = client(c).send(req,
                        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
                    throw new BpmException("BPM-Login fehlgeschlagen (HTTP " + resp.statusCode()
                            + "): " + summarize(resp.body()));
                }
                csrfToken = mapper.readTree(resp.body()).path("csrf_token").asText(null);
                if (csrfToken == null) {
                    throw new BpmException("BPM-Login ohne csrf_token: " + summarize(resp.body()));
                }
                return csrfToken;
            } catch (IOException e) {
                throw new BpmException("BPM-Login nicht moeglich: " + e.getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new BpmException("BPM-Login unterbrochen");
            }
        }
    }

    private static String basic(Creds c) {
        String raw = c.username() + ":" + c.password();
        return "Basic " + Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    /** HttpClient cache; rebuilt when the CA changes (settings). */
    private HttpClient client(Creds c) {
        String ca = c == null ? null : blankToNull(c.caFile());
        HttpClient h = http;
        if (h == null || !java.util.Objects.equals(ca, loadedCaPath)) {
            synchronized (this) {
                if (http == null || !java.util.Objects.equals(ca, loadedCaPath)) {
                    http = HttpClient.newBuilder()
                            .sslContext(sslContext(ca))
                            .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
                            .followRedirects(HttpClient.Redirect.NEVER)
                            .connectTimeout(Duration.ofSeconds(15))
                            .build();
                    loadedCaPath = ca;
                }
                h = http;
            }
        }
        return h;
    }

    /** Discards session state (after password/URL/CA change in the settings). */
    public void resetSession() {
        synchronized (this) {
            csrfToken = null;
            http = null;
            loadedCaPath = null;
        }
    }

    // ---------------- TLS: standard CA + optional corporate CA ----------------

    private SSLContext sslContext(String caPath) {
        try {
            TrustManager[] defaults = trustManagers(null);
            Path pem = caPath == null ? null : Path.of(caPath);
            if (pem == null || !Files.isRegularFile(pem)) {
                return ssl(defaults); // continue without custom CA, may fail if needed
            }
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            List<X509Certificate> customCertificates;
            try (InputStream in = Files.newInputStream(pem)) {
                customCertificates = new ArrayList<>();
                for (var cert : cf.generateCertificates(in)) {
                    customCertificates.add((X509Certificate) cert);
                }
            }
            KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
            ks.load(null, null);
            for (int i = 0; i < customCertificates.size(); i++) {
                ks.setCertificateEntry("custom-ca-" + i, customCertificates.get(i));
            }
            TrustManager[] both = { new CombiningTrustManager(defaults, trustManagers(ks)) };
            return ssl(both);
        } catch (BpmException e) {
            throw e;
        } catch (Exception e) {
            throw new BpmException("TLS-Aufbau fehlgeschlagen: " + e.getMessage());
        }
    }

    private static TrustManager[] trustManagers(KeyStore ks) throws Exception {
        TrustManagerFactory tmf =
                TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(ks);
        return tmf.getTrustManagers();
    }

    private static SSLContext ssl(TrustManager[] tms) throws Exception {
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(null, tms, new SecureRandom());
        return ctx;
    }

    /** accepts when standard OR corporate CA carries the chain. */
    private static final class CombiningTrustManager extends X509ExtendedTrustManager {
        private final TrustManager[] a;
        private final TrustManager[] b;

        CombiningTrustManager(TrustManager[] a, TrustManager[] b) {
            this.a = a;
            this.b = b;
        }

        private X509ExtendedTrustManager first(TrustManager[] set) {
            return (X509ExtendedTrustManager) set[0];
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) { }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType, java.net.Socket socket) { }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType, javax.net.ssl.SSLEngine engine) { }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) throws java.security.cert.CertificateException {
            try {
                first(a).checkServerTrusted(chain, authType);
            } catch (java.security.cert.CertificateException e) {
                first(b).checkServerTrusted(chain, authType);
            }
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType, java.net.Socket socket) throws java.security.cert.CertificateException {
            try {
                first(a).checkServerTrusted(chain, authType, socket);
            } catch (java.security.cert.CertificateException e) {
                first(b).checkServerTrusted(chain, authType, socket);
            }
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType, javax.net.ssl.SSLEngine engine) throws java.security.cert.CertificateException {
            try {
                first(a).checkServerTrusted(chain, authType, engine);
            } catch (java.security.cert.CertificateException e) {
                first(b).checkServerTrusted(chain, authType, engine);
            }
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            List<X509Certificate> all = new ArrayList<>();
            for (TrustManager tm : a) {
                all.addAll(List.of(((X509ExtendedTrustManager) tm).getAcceptedIssuers()));
            }
            for (TrustManager tm : b) {
                all.addAll(List.of(((X509ExtendedTrustManager) tm).getAcceptedIssuers()));
            }
            return all.toArray(new X509Certificate[0]);
        }
    }

    // ---------------- Misc ----------------

    private static final Pattern CD_FILE = Pattern.compile("filename\\*?=?\"?([^\";]+)\"?");
    private static final Pattern CD_FILE_EXT = Pattern.compile("filename\\*=([^;]+)");

    static String filenameFromDisposition(String header) {
        // prefer RFC 5987: filename*=UTF-8''… usually follows the ASCII fallback
        var me = CD_FILE_EXT.matcher(header);
        if (me.find()) {
            String v = me.group(1).trim();
            int q = v.indexOf("''");
            if (q >= 0) {
                v = v.substring(q + 2);
            }
            try {
                return URLDecoder.decode(v, StandardCharsets.UTF_8);
            } catch (IllegalArgumentException ignored) {
                return v;
            }
        }
        var m = CD_FILE.matcher(header);
        if (m.find()) {
            try {
                return URLDecoder.decode(m.group(1).trim(), StandardCharsets.UTF_8);
            } catch (IllegalArgumentException e) {
                return m.group(1).trim();
            }
        }
        return "bpm-export.twx";
    }

    private static String summarize(String body) {
        if (body == null || body.isBlank()) {
            return "(leere Antwort)";
        }
        String flat = body.replaceAll("\\s+", " ").trim();
        try {
            JsonNode j = new ObjectMapper().readTree(body);
            if (j.hasNonNull("error_message")) {
                flat = j.path("error_message").asText();
            }
        } catch (IOException ignored) { }
        return flat.length() > 300 ? flat.substring(0, 300) + "…" : flat;
    }
}

package io.twxav2.service;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.net.URI;
import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/** Restricts BAW credentials to explicitly trusted HTTPS origins. */
@ApplicationScoped
public class BpmTargetPolicy {

    @ConfigProperty(name = "twxav2.bpm.allowed-origins")
    Optional<String> configuredOrigins;

    @ConfigProperty(name = "twxav2.bpm.default-base-url")
    Optional<String> defaultBaseUrl;

    public void validate(String baseUrl) {
        URI uri;
        try {
            uri = URI.create(baseUrl);
        } catch (RuntimeException e) {
            throw invalid("syntax");
        }
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                || uri.getPort() == 0 || uri.getPort() > 65535) {
            throw invalid("HTTPS URL without user-info, query, or fragment required");
        }

        Set<String> allowed = allowedOrigins();
        if (allowed.isEmpty() || !allowed.contains(origin(uri))) {
            throw invalid("origin is not in BPM_ALLOWED_ORIGINS or the configured default URL");
        }
    }

    private Set<String> allowedOrigins() {
        String raw = configuredOrigins.orElse("").trim();
        if (raw.isBlank()) {
            String defaultUrl = defaultBaseUrl.orElse("").trim();
            if (defaultUrl.isBlank()) {
                return Set.of();
            }
            URI uri;
            try {
                uri = URI.create(defaultUrl);
            } catch (RuntimeException e) {
                throw new IllegalStateException("Invalid BPM default base URL configuration");
            }
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                    || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) {
                throw new IllegalStateException("BPM_DEFAULT_BASE_URL must be an HTTPS URL without credentials");
            }
            return Set.of(origin(uri));
        }
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .map(this::parseAllowedOrigin)
                .collect(Collectors.toUnmodifiableSet());
    }

    private String parseAllowedOrigin(String value) {
        URI uri;
        try {
            uri = URI.create(value);
        } catch (RuntimeException e) {
            throw new IllegalStateException("Invalid BPM allowed origin configuration");
        }
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                || (uri.getPath() != null && !uri.getPath().isEmpty() && !uri.getPath().equals("/"))
                || uri.getPort() == 0 || uri.getPort() > 65535) {
            throw new IllegalStateException("BPM_ALLOWED_ORIGINS entries must be HTTPS origins without paths");
        }
        return origin(uri);
    }

    private static String origin(URI uri) {
        int port = uri.getPort() < 0 ? 443 : uri.getPort();
        return "https://" + uri.getHost().toLowerCase(Locale.ROOT) + ":" + port;
    }

    private static WebApplicationException invalid(String reason) {
        return new WebApplicationException(Response.status(400)
                .entity(java.util.Map.of("error", "Ungueltige Basis-URL (BAW: " + reason + ")")).build());
    }
}

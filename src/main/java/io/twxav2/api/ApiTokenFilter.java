package io.twxav2.api;

import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.PreMatching;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.Optional;

/**
 * Optional API token (B9, CI integration): if twxav2.api.token is set,
 * the machine endpoint /api/export requires the X-API-Token header (or
 * ?token=). The UI endpoints (including /gate) stay open so the
 * web UI remains usable without a secret. Without the property: off.
 */
@Provider
@PreMatching
public class ApiTokenFilter implements ContainerRequestFilter {

    /**
     * Optional: configured as an empty string (= default) it counts as not set —
     * SmallRye throws SRCFG00040 for an empty string against a normal String injection,
     * hence Optional.
     */
    @ConfigProperty(name = "twxav2.api.token")
    Optional<String> token;

    /** Paths that require proof when a token is set (exact route, no prefix slip). */
    private static boolean guarded(String path) {
        return path.equals("/api/export") || path.startsWith("/api/export/");
    }

    @Override
    public void filter(ContainerRequestContext ctx) {
        if (token.isEmpty() || token.get().isBlank()) {
            return;
        }
        if (!guarded(ctx.getUriInfo().getPath())) {
            return;
        }
        String given = ctx.getHeaderString("X-API-Token");
        if (given == null || given.isBlank()) {
            given = ctx.getUriInfo().getQueryParameters().getFirst("token");
        }
        // constant-time comparison (no timing probing against the API token)
        boolean ok = given != null && java.security.MessageDigest.isEqual(
                token.get().getBytes(java.nio.charset.StandardCharsets.UTF_8),
                given.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        if (!ok) {
            ctx.abortWith(Response.status(401)
                    .entity(java.util.Map.of("error", "API-Token fehlt oder ungueltig"))
                    .build());
        }
    }
}

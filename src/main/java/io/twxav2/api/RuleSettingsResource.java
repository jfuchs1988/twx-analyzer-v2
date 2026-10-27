package io.twxav2.api;

import io.twxav2.analysis.RuleSettings;
import io.twxav2.analysis.TwxRule;
import io.twxav2.service.RuleSettingsService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Rule catalog and rule settings: rules on/off, severity and impact
 * overridable, thresholds configurable; everything exportable as JSON
 * (global as the default, per snapshot overriding).
 */
@Path("/api/rules")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@ApplicationScoped
public class RuleSettingsResource {

    @Inject
    Instance<TwxRule> rules;

    @Inject
    RuleSettingsService settingsService;

    public record RuleDto(String id, String title, String category, String severity,
                          int impact, boolean enabled, boolean customized, String rationale,
                          String guidance, String titleEn, String categoryEn,
                          String rationaleEn, String guidanceEn) { }

    /** Catalog of all rules with effective (global) settings. */
    @GET
    @Transactional
    public List<RuleDto> catalog() {
        RuleSettings eff = settingsService.effective(null);
        List<RuleDto> out = new ArrayList<>();
        List<TwxRule> sorted = new ArrayList<>(rules.stream().toList());
        sorted.sort(Comparator.comparing(TwxRule::id));
        for (TwxRule r : sorted) {
            RuleSettings.Override ov = eff.override(r.id());
            boolean disabled = ov != null && ov.enabled != null
                    ? !ov.enabled : !r.defaultEnabled();
            String sev = ov != null && ov.severity != null ? ov.severity.name() : r.defaultSeverity().name();
            int impact = ov != null && ov.impact != null ? ov.impact : r.impact();
            out.add(new RuleDto(r.id(), r.title(), r.category(), sev, impact, !disabled,
                    ov != null && !ov.isEmpty(), r.rationale(), r.guidance(),
                    r.titleEn(), r.categoryEn(), r.rationaleEn(), r.guidanceEn()));
        }
        return out;
    }

    /** Global default settings (JSON export/import). */
    @GET
    @Path("settings")
    @Transactional
    public Response global() {
        return Response.ok(settingsService.rawJson(null))
                .header("Content-Type", "application/json").build();
    }

    @PUT
    @Path("settings")
    public Response saveGlobal(String json) {
        try {
            settingsService.save(null, json);
            return Response.noContent().build();
        } catch (IllegalArgumentException e) {
            return Response.status(400).entity(Map.of("error", e.getMessage())).build();
        }
    }

    /** Template with the default thresholds (for import on other instances). */
    @GET
    @Path("settings/defaults")
    public Response defaults() {
        return Response.ok(settingsService.defaultThresholdsJson())
                .header("Content-Type", "application/json").build();
    }

    /** Snapshot settings (override the global ones). */
    @GET
    @Path("settings/snapshot/{id}")
    @Transactional
    public Response snapshot(@PathParam("id") Long id) {
        return Response.ok(settingsService.rawJson(id))
                .header("Content-Type", "application/json").build();
    }

    @PUT
    @Path("settings/snapshot/{id}")
    public Response saveSnapshot(@PathParam("id") Long id, String json) {
        try {
            settingsService.save(id, json);
            return Response.noContent().build();
        } catch (IllegalArgumentException e) {
            return Response.status(400).entity(Map.of("error", e.getMessage())).build();
        }
    }

    @DELETE
    @Path("settings/snapshot/{id}")
    public Response deleteSnapshot(@PathParam("id") Long id) {
        settingsService.deleteSnapshotSettings(id);
        return Response.noContent().build();
    }
}

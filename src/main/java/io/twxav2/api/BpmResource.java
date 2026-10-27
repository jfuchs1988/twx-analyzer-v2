package io.twxav2.api;

import io.smallrye.common.annotation.Blocking;
import io.twxav2.api.dto.BpmSettingsDto;
import io.twxav2.api.dto.SnapshotDto;
import io.twxav2.model.BpmSettings;
import io.twxav2.model.Snapshot;
import io.twxav2.service.BpmClient;
import io.twxav2.service.IngestService;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.resteasy.reactive.RestQuery;

import java.nio.file.Files;
import java.util.List;
import java.util.Map;

/**
 * BPM direct import (ADR-010): maintain settings, list containers/snapshots
 * (read-only GETs against the BAW operations REST interface), and load + analyze TWX.
 */
@Path("/api/bpm")
@Produces(MediaType.APPLICATION_JSON)
@Blocking
public class BpmResource {

    public record SettingsIn(String baseUrl, String username, String password, String caFile) { }

    public record ImportIn(String acronym, String version) { }

    @Inject
    BpmClient bpm;

    @Inject
    io.twxav2.service.BpmTargetPolicy bpmTargetPolicy;

    @Inject
    IngestService ingestService;

    @Inject
    io.twxav2.service.IngestQueue ingestQueue;

    @ConfigProperty(name = "twxav2.bpm.default-base-url")
    java.util.Optional<String> defaultBaseUrl;

    @Inject
    io.twxav2.service.SecretsCrypto secrets;

    @GET
    @Path("settings")
    @Transactional
    public BpmSettingsDto settingsGet() {
        BpmSettings s = BpmSettings.get();
        String base = s.baseUrl != null && !s.baseUrl.isBlank() ? s.baseUrl : defaultBaseUrl.orElse("");
        return new BpmSettingsDto(
                base == null || base.isBlank() ? null : base,
                s.username, s.password != null && !s.password.isBlank(), s.caFile);
    }

    @PUT
    @Path("settings")
    @Consumes(MediaType.APPLICATION_JSON)
    @Transactional
    public BpmSettingsDto settingsPut(SettingsIn in) {
        BpmSettings s = BpmSettings.get();
        if (in.baseUrl() != null) {
            String u = in.baseUrl().trim();
            while (u.endsWith("/")) {
                u = u.substring(0, u.length() - 1);
            }
            if (!u.isBlank()) {
                bpmTargetPolicy.validate(u);
            }
            s.baseUrl = u.isBlank() ? null : u;
        }
        if (in.username() != null) {
            s.username = in.username().trim();
        }
        // empty field = keep the password (the UI never displays it)
        if (in.password() != null && !in.password().isEmpty()) {
            s.password = secrets.encrypt(in.password());
        }
        if (in.caFile() != null) {
            String ca = in.caFile().trim();
            s.caFile = ca.isEmpty() ? null : ca;
        }
        s.persist();
        bpm.resetSession();
        return settingsGet();
    }

    @POST
    @Path("test")
    public Response test() {
        BpmClient.Creds c = bpm.creds();
        if (!c.complete()) {
            return Response.status(400).entity(Map.of("error",
                    "Bitte Basis-URL, Benutzer und Passwort in den Settings hinterlegen")).build();
        }
        try {
            return Response.ok(Map.of("ok", true, "message", bpm.testConnection(c))).build();
        } catch (BpmClient.BpmException e) {
            return Response.status(400).entity(Map.of("ok", false, "error", e.getMessage())).build();
        }
    }

    @GET
    @Path("containers")
    public Response containers(@RestQuery String q) {
        BpmClient.Creds c = bpm.creds();
        if (!c.complete()) {
            return settingsMissing();
        }
        try {
            List<BpmClient.ContainerInfo> list = bpm.listContainers(c, q);
            return Response.ok(list).build();
        } catch (BpmClient.BpmException e) {
            return bpmError(e);
        }
    }

    @GET
    @Path("snapshots")
    public Response snapshots(@RestQuery String acronym) {
        BpmClient.Creds c = bpm.creds();
        if (!c.complete()) {
            return settingsMissing();
        }
        if (acronym == null || acronym.isBlank()) {
            return Response.status(400).entity(Map.of("error", "Parameter acronym fehlt")).build();
        }
        try {
            List<BpmClient.SnapshotInfo> list = bpm.listSnapshots(c, acronym);
            return Response.ok(list).build();
        } catch (BpmClient.BpmException e) {
            return bpmError(e);
        }
    }

    /** Enqueues the TWX import from BPM into the ingest queue (parallel workers). */
    @POST
    @Path("import")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response importSnapshot(ImportIn in) throws Exception {
        BpmClient.Creds c = bpm.creds();
        if (!c.complete()) {
            return settingsMissing();
        }
        if (in == null || isBlank(in.acronym()) || isBlank(in.version())) {
            return Response.status(400).entity(Map.of("error", "acronym und version erforderlich")).build();
        }
        // No more duplicate abort: re-import is intended (replace import).
        // If an analysis of the same project+version already exists, the backstop in
        // IngestService.ingest() deletes the old analysis after export and ingestion
        // succeeded (an export error leaves the old snapshot untouched).
        String acronym = in.acronym().trim();
        String version = in.version().trim();
        String dedupKey = "bpm:" + acronym.toLowerCase() + "/" + version.toLowerCase();
        if (ingestQueue.isPending(dedupKey)) {
            return Response.status(409).entity(Map.of(
                    "error", acronym + " " + version + " liegt bereits in der Warteschlange")).build();
        }
        io.twxav2.service.IngestQueue.Reservation reservation = ingestQueue.reserve(bpm.maxExportBytes());
        if (reservation == null) {
            return Response.status(429).entity(Map.of(
                    "error", "Importwarteschlange oder temporaeres Speicherbudget voll; spaeter erneut versuchen")).build();
        }
        Long taskId;
        try {
            taskId = ingestQueue.submit(acronym + " · " + version, dedupKey,
                io.twxav2.service.IngestQueue.Phase.EXPORT, reservation, null, task -> {
                    java.nio.file.Path tmp = null;
                    try {
                        tmp = ingestService.createStagingFile("bpm-import-", ".twx");
                        BpmClient.Exported ex = bpm.exportSnapshot(bpm.creds(), acronym, version, tmp);
                        Snapshot s = ingestService.ingest(ex.filename(), ex.sizeBytes(), tmp, task::advance);
                        tmp = null; // ingest has moved the file
                        if (s.status == Snapshot.Status.ERROR) {
                            task.fail(s.statusMessage);
                            return null;
                        }
                        return s.id;
                    } catch (BpmClient.BpmException e) {
                        task.fail(e.getMessage());
                        return null;
                    } catch (Exception e) {
                        task.fail(e.getClass().getSimpleName() + ": " + e.getMessage());
                        return null;
                    } finally {
                        if (tmp != null) {
                            try {
                                Files.deleteIfExists(tmp);
                            } catch (java.io.IOException ignored) {
                            }
                        }
                    }
                });
        } catch (io.twxav2.service.IngestQueue.QueueFullException e) {
            return Response.status(429).entity(Map.of(
                    "error", "Importwarteschlange voll; spaeter erneut versuchen")).build();
        } catch (RuntimeException e) {
            reservation.close();
            throw e;
        }
        if (taskId == null) {
            return Response.status(409).entity(Map.of("error",
                    acronym + " " + version + " liegt bereits in der Warteschlange")).build();
        }
        return Response.status(202).entity(Map.of("taskId", taskId)).build();
    }

    private boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private Response settingsMissing() {
        return Response.status(400).entity(Map.of("error",
                "BPM-Zugang nicht konfiguriert – bitte zuerst die Settings ausfuellen")).build();
    }

    private Response bpmError(BpmClient.BpmException e) {
        return Response.status(502).entity(Map.of("error", e.getMessage())).build();
    }
}

package io.twxav2.api;

import io.twxav2.service.IngestQueue;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import java.util.List;

/**
 * Progress of ingestions (uploads and BPM imports) for the UI; survives
 * page changes and browser refresh because the status lives server-side in the
 * queue. Returns all active, queued, and recently finished
 * jobs (older first).
 */
@Path("/api/ingest")
@Produces(MediaType.APPLICATION_JSON)
public class IngestResource {

    @Inject
    IngestQueue queue;

    @GET
    @Path("status")
    public List<IngestQueue.TaskStatus> status() {
        return queue.list();
    }
}

package io.twxav2.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class IngestStagingTest {

    @TempDir
    Path tempDir;

    @Test
    void startupCleanupRemovesAbandonedStagingFilesOnly() throws Exception {
        IngestService service = new IngestService();
        service.uploadsDir = tempDir.resolve("uploads").toString();
        Path stagingFile = service.createStagingFile("synthetic-", ".twx");
        Files.writeString(stagingFile, "synthetic");
        Path multipartFile = Path.of(service.uploadsDir).resolve(".multipart/request.tmp");
        Files.createDirectories(multipartFile.getParent());
        Files.writeString(multipartFile, "synthetic multipart data");
        Path retainedUpload = Path.of(service.uploadsDir).resolve("retained.twx");
        Files.createDirectories(retainedUpload.getParent());
        Files.writeString(retainedUpload, "kept");

        service.cleanupTemporaryDirectories();

        assertFalse(Files.exists(stagingFile));
        assertFalse(Files.exists(multipartFile));
        assertEquals("kept", Files.readString(retainedUpload));
    }

    @Test
    void stagingCreationFailureDoesNotCreateOutsideUploadDirectory() throws Exception {
        IngestService service = new IngestService();
        Path uploadsFile = tempDir.resolve("not-a-directory");
        Files.writeString(uploadsFile, "synthetic");
        service.uploadsDir = uploadsFile.toString();

        assertThrows(java.io.IOException.class, () -> service.createStagingFile("synthetic-", ".twx"));
        assertEquals("synthetic", Files.readString(uploadsFile));
    }
}

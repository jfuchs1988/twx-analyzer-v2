package io.twxav2.service;

import jakarta.ws.rs.WebApplicationException;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class BpmTargetPolicyTest {

    @Test
    void acceptsOnlyHttpsOriginsInExplicitAllowlist() {
        BpmTargetPolicy policy = policy("https://bpm.example.test:9443,https://backup.example.test", null);

        assertDoesNotThrow(() -> policy.validate("https://bpm.example.test:9443/BPM"));
        assertDoesNotThrow(() -> policy.validate("https://backup.example.test/"));
        assertRejected(policy, "http://bpm.example.test:9443/BPM");
        assertRejected(policy, "https://other.example.test/BPM");
        assertRejected(policy, "https://bpm.example.test/BPM");
    }

    @Test
    void defaultUrlProvidesOnlyItsOriginAsFallback() {
        BpmTargetPolicy policy = policy("", "https://bpm.example.test:9443/BPM");

        assertDoesNotThrow(() -> policy.validate("https://bpm.example.test:9443/BPM"));
        assertRejected(policy, "https://other.example.test/BPM");
        assertRejected(policy, "http://bpm.example.test:9443/BPM");
    }

    @Test
    void refusesCredentialsQueriesFragmentsAndMissingAllowlist() {
        BpmTargetPolicy policy = policy("https://bpm.example.test", null);
        assertRejected(policy, "https://user:pass@bpm.example.test");
        assertRejected(policy, "https://bpm.example.test/path?redirect=other");
        assertRejected(policy, "https://bpm.example.test/path#fragment");
        assertRejected(policy("", null), "https://bpm.example.test");
    }

    private static BpmTargetPolicy policy(String allowedOrigins, String defaultUrl) {
        BpmTargetPolicy policy = new BpmTargetPolicy();
        policy.configuredOrigins = Optional.ofNullable(allowedOrigins);
        policy.defaultBaseUrl = Optional.ofNullable(defaultUrl);
        return policy;
    }

    private static void assertRejected(BpmTargetPolicy policy, String url) {
        WebApplicationException error = assertThrows(WebApplicationException.class,
                () -> policy.validate(url), url);
        assertEquals(400, error.getResponse().getStatus());
    }
}

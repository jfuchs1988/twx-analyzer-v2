package io.twxav2.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Plain JUnit tests for SecretsCrypto (no Quarkus container needed: the
 * secret is set directly on the field). PBKDF2 with 210k iterations runs
 * at full strength on purpose — runtime is acceptable.
 */
class SecretsCryptoTest {

    private static SecretsCrypto crypto(String secret) {
        SecretsCrypto c = new SecretsCrypto();
        c.secret = java.util.Optional.ofNullable(secret);
        return c;
    }

    @Test
    void enabledAfterSecret() {
        assertFalse(crypto(null).enabled());
        assertFalse(crypto("  ").enabled());
        assertTrue(crypto("s3cr3t").enabled());
    }

    @Test
    void withoutSecretPlainTextPassThrough() {
        SecretsCrypto c = crypto("");
        assertEquals("pw", c.encrypt("pw"));
        assertEquals("pw", c.decrypt("pw"));
        assertNull(c.encrypt(null));
        assertEquals("", c.encrypt(""));
        assertNull(c.decrypt(null));
        assertEquals("", c.decrypt(""));
    }

    @Test
    void roundTripWithSaltAndIv() {
        SecretsCrypto c = crypto("master-secret");
        String a = c.encrypt("geheim17");
        String b = c.encrypt("geheim17"); // fresh salt+IV -> different cipher text
        assertTrue(a.startsWith("v1:"));
        assertNotEquals(a, b);
        assertEquals("geheim17", c.decrypt(a));
        assertEquals("geheim17", c.decrypt(b));
        assertTrue(SecretsCrypto.isEncrypted(a));
        assertFalse(SecretsCrypto.isEncrypted("geheim17"));
        assertFalse(SecretsCrypto.isEncrypted(null));
    }

    @Test
    void alreadyEncryptedIsNotAgainEncrypted() {
        SecretsCrypto c = crypto("master-secret");
        String once = c.encrypt("pw");
        assertEquals(once, c.encrypt(once));
    }

    @Test
    void plainTextAtDecryptPassedThrough() {
        assertEquals("alt-plain", crypto("master-secret").decrypt("alt-plain"));
    }

    @Test
    void wrongSecretFails() {
        String stored = crypto("secret-a").encrypt("pw");
        assertThrows(IllegalStateException.class, () -> crypto("secret-b").decrypt(stored));
    }

    @Test
    void encryptedWithoutSecretFails() {
        String stored = crypto("secret-a").encrypt("pw");
        assertThrows(IllegalStateException.class, () -> crypto("").decrypt(stored));
    }

    @Test
    void damagedFormatFails() {
        SecretsCrypto c = crypto("secret-a");
        assertThrows(IllegalStateException.class, () -> c.decrypt("v1:nur:zwei"));
        assertThrows(IllegalStateException.class, () -> c.decrypt("v1:###:###:###"));
    }
}

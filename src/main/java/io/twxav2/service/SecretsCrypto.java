package io.twxav2.service;

import io.quarkus.runtime.LaunchMode;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Encrypts secrets in the database (BAW password, see ADR-011:
 * master secret via env, nothing in the repo). AES-256-GCM; the key is derived via
 * PBKDF2-HMAC-SHA256 from {@code twxav2.security.db-secret}.
 * Per record, salt (16 B) and IV (12 B) are freshly generated with SecureRandom;
 * the format is {@code v1:base64(salt):base64(iv):base64(ciphertext)}.
 * Without a configured secret the storage stays plaintext (development); plaintext
 * already stored is passed through transparently when decrypting. A temporary previous
 * secret supports re-encryption during an explicit key rotation.
 */
@ApplicationScoped
public class SecretsCrypto {

    static final String PREFIX = "v1:";
    static final int SALT_BYTES = 16;
    static final int IV_BYTES = 12;
    static final int KEY_BITS = 256;
    static final int PBKDF2_ITERATIONS = 210_000;
    private static final int TAG_BITS = 128;

    private static final SecureRandom RANDOM = new SecureRandom();

    /**
     * Optional: kleiner-rye throws SRCFG00040 on an empty string against a normal
     * String injection — empty env default = not set.
     */
    @ConfigProperty(name = "twxav2.security.db-secret")
    java.util.Optional<String> secret;

    @ConfigProperty(name = "twxav2.security.db-secret-previous")
    java.util.Optional<String> previousSecret = java.util.Optional.empty();

    public boolean enabled() {
        return secret.isPresent() && !secret.get().isBlank();
    }

    /** Fail closed when the packaged production profile has no encryption key. */
    void validateConfiguration() {
        validateConfiguration(LaunchMode.current() == LaunchMode.NORMAL ? "prod" : "test");
    }

    void validateConfiguration(String profile) {
        if ("prod".equals(profile) && !enabled()) {
            throw new IllegalStateException(
                    "DB_SECRET must be configured in the production profile; refusing plaintext secret storage");
        }
    }

    /** True when a distinct previous key was supplied for a one-time rotation. */
    boolean rotationConfigured() {
        return enabled() && previousSecret != null && previousSecret.isPresent()
                && !previousSecret.get().isBlank() && !previousSecret.get().equals(secret.get());
    }

    /** Convert legacy plaintext or re-encrypt a value from DB_SECRET_PREVIOUS. */
    public String migrate(String stored) {
        if (stored == null || stored.isEmpty()) {
            return stored;
        }
        if (isEncrypted(stored)) {
            if (!rotationConfigured()) {
                return stored;
            }
            try {
                return encrypt(decryptWith(previousSecret.get(), stored));
            } catch (IllegalStateException previousKeyFailed) {
                // A prior startup may already have committed the new ciphertext
                // before the operator removed DB_SECRET_PREVIOUS.
                try {
                    decryptWith(secret.get(), stored);
                    return stored;
                } catch (IllegalStateException currentKeyFailed) {
                    previousKeyFailed.addSuppressed(currentKeyFailed);
                    throw previousKeyFailed;
                }
            }
        }
        return encrypt(stored);
    }

    /** Encrypts; unchanged without a secret or for an already-marked value. */
    public String encrypt(String plain) {
        if (plain == null || plain.isEmpty() || !enabled() || plain.startsWith(PREFIX)) {
            return plain;
        }
        try {
            byte[] salt = new byte[SALT_BYTES];
            RANDOM.nextBytes(salt);
            byte[] iv = new byte[IV_BYTES];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, deriveKey(secret.get(), salt),
                    new GCMParameterSpec(TAG_BITS, iv));
            byte[] ct = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            Base64.Encoder b64 = Base64.getEncoder();
            return PREFIX + b64.encodeToString(salt) + ":" + b64.encodeToString(iv)
                    + ":" + b64.encodeToString(ct);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Verschluesseln fehlgeschlagen: " + e.getMessage(), e);
        }
    }

    /** Decrypts v1:-values; plaintext (no prefix) is passed through. */
    public String decrypt(String stored) {
        if (stored == null || stored.isEmpty() || !stored.startsWith(PREFIX)) {
            return stored;
        }
        if (!enabled()) {
            throw new IllegalStateException(
                    "Verschluesseltes Geheimnis gefunden, aber twxav2.security.db-secret ist nicht gesetzt");
        }
        return decryptWith(secret.get(), stored);
    }

    private String decryptWith(String keySecret, String stored) {
        try {
            String[] parts = stored.substring(PREFIX.length()).split(":");
            if (parts.length != 3) {
                throw new IllegalArgumentException("unvollstaendiges v1-Format");
            }
            Base64.Decoder b64 = Base64.getDecoder();
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, deriveKey(keySecret, b64.decode(parts[0])),
                    new GCMParameterSpec(TAG_BITS, b64.decode(parts[1])));
            return new String(cipher.doFinal(b64.decode(parts[2])), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException(
                    "Entschluesseln fehlgeschlagen (falsches Secret oder veraenderter Wert): "
                            + e.getMessage(), e);
        }
    }

    public static boolean isEncrypted(String stored) {
        return stored != null && stored.startsWith(PREFIX);
    }

    private SecretKey deriveKey(String keySecret, byte[] salt) throws GeneralSecurityException {
        PBEKeySpec spec = new PBEKeySpec(keySecret.toCharArray(), salt, PBKDF2_ITERATIONS, KEY_BITS);
        try {
            byte[] key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                    .generateSecret(spec).getEncoded();
            return new SecretKeySpec(key, "AES");
        } finally {
            spec.clearPassword();
        }
    }
}

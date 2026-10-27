package io.twxav2.service;

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
 * already stored is passed through transparently when decrypting.
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

    public boolean enabled() {
        return secret.isPresent() && !secret.get().isBlank();
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
            cipher.init(Cipher.ENCRYPT_MODE, deriveKey(salt), new GCMParameterSpec(TAG_BITS, iv));
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
        try {
            String[] parts = stored.substring(PREFIX.length()).split(":");
            if (parts.length != 3) {
                throw new IllegalArgumentException("unvollstaendiges v1-Format");
            }
            Base64.Decoder b64 = Base64.getDecoder();
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, deriveKey(b64.decode(parts[0])),
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

    private SecretKey deriveKey(byte[] salt) throws GeneralSecurityException {
        PBEKeySpec spec = new PBEKeySpec(secret.get().toCharArray(), salt, PBKDF2_ITERATIONS, KEY_BITS);
        try {
            byte[] key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                    .generateSecret(spec).getEncoded();
            return new SecretKeySpec(key, "AES");
        } finally {
            spec.clearPassword();
        }
    }
}

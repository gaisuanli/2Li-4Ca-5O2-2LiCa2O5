package edu.gzhu.sitesafe.service;

import edu.gzhu.sitesafe.common.AppException;
import edu.gzhu.sitesafe.config.AiAgentProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/**
 * Encrypts user-supplied provider credentials at rest. The master key is never
 * generated or persisted by the application: it must be supplied by the
 * deployment environment as a Base64-encoded 32-byte value.
 */
@Service
public class AiAgentCredentialCipher {
    private static final Logger log = LoggerFactory.getLogger(AiAgentCredentialCipher.class);
    private static final String VERSION = "v1";
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecretKey masterKey;
    private final SecureRandom secureRandom = new SecureRandom();

    public AiAgentCredentialCipher(AiAgentProperties properties) {
        this.masterKey = parseMasterKey(properties.getCredentialEncryptionKey());
    }

    public boolean available() {
        return masterKey != null;
    }

    public String encrypt(long userId, String plaintext) {
        requireAvailable();
        byte[] nonce = new byte[NONCE_BYTES];
        secureRandom.nextBytes(nonce);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, masterKey, new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(aad(userId));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
            return VERSION + "." + encoder.encodeToString(nonce) + "." + encoder.encodeToString(ciphertext);
        } catch (GeneralSecurityException ex) {
            log.error("Could not encrypt an AI Agent provider credential ({})", ex.getClass().getSimpleName());
            throw unavailable("AI_AGENT_CREDENTIAL_STORAGE_UNAVAILABLE",
                    "AI Agent 凭据加密服务暂不可用");
        }
    }

    public String decrypt(long userId, String encoded) {
        requireAvailable();
        try {
            String[] parts = encoded == null ? new String[0] : encoded.split("\\.", -1);
            if (parts.length != 3 || !VERSION.equals(parts[0])) throw new IllegalArgumentException("version");
            Base64.Decoder decoder = Base64.getUrlDecoder();
            byte[] nonce = decoder.decode(parts[1]);
            byte[] ciphertext = decoder.decode(parts[2]);
            if (nonce.length != NONCE_BYTES || ciphertext.length < 16 || ciphertext.length > 4096) {
                throw new IllegalArgumentException("length");
            }
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, masterKey, new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(aad(userId));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException ex) {
            log.warn("Could not decrypt an AI Agent provider credential ({})", ex.getClass().getSimpleName());
            throw unavailable("AI_AGENT_CREDENTIAL_UNAVAILABLE",
                    "当前 AI Agent 凭据无法解密，请重新保存服务商配置");
        }
    }

    private SecretKey parseMasterKey(String encoded) {
        if (encoded == null || encoded.isBlank()) return null;
        byte[] decoded = null;
        try {
            decoded = Base64.getDecoder().decode(encoded.trim());
            if (decoded.length != 32) {
                log.warn("AI Agent credential encryption key must decode to exactly 32 bytes");
                return null;
            }
            return new SecretKeySpec(decoded, "AES");
        } catch (IllegalArgumentException ex) {
            log.warn("AI Agent credential encryption key is not valid Base64");
            return null;
        } finally {
            if (decoded != null) Arrays.fill(decoded, (byte) 0);
        }
    }

    public void requireAvailable() {
        if (!available()) {
            throw unavailable("AI_AGENT_CREDENTIAL_STORAGE_UNAVAILABLE",
                    "服务端未配置 AI Agent 凭据加密主密钥，暂不能保存或使用个人服务商配置");
        }
    }

    private byte[] aad(long userId) {
        return ("sitesafe:ai-agent-provider:user:" + userId).getBytes(StandardCharsets.UTF_8);
    }

    private AppException unavailable(String code, String message) {
        return new AppException(HttpStatus.SERVICE_UNAVAILABLE, code, message);
    }
}

package edu.gzhu.sitesafe.service;

import edu.gzhu.sitesafe.common.AppException;
import edu.gzhu.sitesafe.config.AiAgentProperties;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiAgentCredentialCipherTest {
    @Test
    void aesGcmRoundTripUsesRandomNonceAndBindsCiphertextToUser() {
        AiAgentProperties properties = new AiAgentProperties();
        properties.setCredentialEncryptionKey(
                "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=");
        AiAgentCredentialCipher cipher = new AiAgentCredentialCipher(properties);

        String first = cipher.encrypt(7L, "private-api-key");
        String second = cipher.encrypt(7L, "private-api-key");
        assertTrue(first.startsWith("v1."));
        assertFalse(first.contains("private-api-key"));
        assertNotEquals(first, second);
        assertEquals("private-api-key", cipher.decrypt(7L, first));
        assertEquals("AI_AGENT_CREDENTIAL_UNAVAILABLE",
                assertThrows(AppException.class, () -> cipher.decrypt(8L, first)).code());
        assertEquals("AI_AGENT_CREDENTIAL_UNAVAILABLE",
                assertThrows(AppException.class,
                        () -> cipher.decrypt(7L, first.substring(0, first.length() - 1) + "A")).code());
    }

    @Test
    void missingOrInvalidMasterKeyCannotEncrypt() {
        AiAgentCredentialCipher missing = new AiAgentCredentialCipher(new AiAgentProperties());
        assertFalse(missing.available());
        assertEquals("AI_AGENT_CREDENTIAL_STORAGE_UNAVAILABLE",
                assertThrows(AppException.class, () -> missing.encrypt(1L, "secret")).code());

        AiAgentProperties invalidProperties = new AiAgentProperties();
        invalidProperties.setCredentialEncryptionKey("not-a-32-byte-base64-key");
        assertFalse(new AiAgentCredentialCipher(invalidProperties).available());
    }
}

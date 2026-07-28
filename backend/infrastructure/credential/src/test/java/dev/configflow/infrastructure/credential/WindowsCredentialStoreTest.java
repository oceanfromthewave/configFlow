package dev.configflow.infrastructure.credential;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.configflow.domain.credential.Credential;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

/**
 * Round-trips the real Windows Credential Manager, so it only runs on Windows. Every
 * credential it writes is removed in {@link #cleanUp()} — targets are random UUIDs, so
 * nothing here can touch a credential the user actually stored.
 */
@EnabledOnOs(OS.WINDOWS)
class WindowsCredentialStoreTest {

    private final WindowsCredentialStore store = new WindowsCredentialStore();
    private final List<String> written = new ArrayList<>();

    /** Stores through the adapter and remembers the key so {@link #cleanUp()} can delete it. */
    private String store(Credential credential) {
        String key = store.store(credential);
        written.add(key);
        return key;
    }

    @AfterEach
    void cleanUp() {
        for (String key : written) {
            try {
                store.delete(key);
            } catch (RuntimeException ignored) {
                // Best-effort: a failed cleanup must not mask the test's own result.
            }
        }
    }

    @Test
    void storesAndReadsBackEveryField() {
        String key = store(new Credential("github.com", "https", "alice", "s3cr3t".toCharArray()));

        Credential found = store.find(key).orElseThrow();
        assertEquals("github.com", found.host());
        assertEquals("https", found.protocol());
        assertEquals("alice", found.username());
        assertArrayEquals("s3cr3t".toCharArray(), found.secret());
    }

    @Test
    void tokenOnlyCredentialReadsBackWithNullUsername() {
        // save() passes null for token-only auth; it must survive the round trip as null,
        // not as an empty string that would read as a named-but-blank account.
        String key = store(new Credential("github.com", "https", null, "ghp_token".toCharArray()));

        Credential found = store.find(key).orElseThrow();
        assertNull(found.username());
        assertArrayEquals("ghp_token".toCharArray(), found.secret());
    }

    @Test
    void keepsTheSecretEvenAfterTheCallerWipesItsArray() {
        char[] secret = "s3cr3t".toCharArray();
        String key = store(new Credential("github.com", "https", "alice", secret));

        // CredentialService wipes the caller's array right after store() returns. Windows
        // copied the blob during CredWrite, so the stored secret must be untouched.
        Arrays.fill(secret, '\0');

        assertArrayEquals("s3cr3t".toCharArray(), store.find(key).orElseThrow().secret());
    }

    @Test
    void findReturnsACopyTheCallerCanWipeWithoutTouchingTheStore() {
        String key = store(new Credential("github.com", "https", "alice", "s3cr3t".toCharArray()));

        // Each find decodes a fresh array from the OS store, so wiping one result cannot
        // reach back and corrupt the next read.
        Arrays.fill(store.find(key).orElseThrow().secret(), '\0');

        assertArrayEquals("s3cr3t".toCharArray(), store.find(key).orElseThrow().secret());
    }

    @Test
    void findReturnsEmptyForAnUnknownKey() {
        assertTrue(store.find("configflow:does-not-exist").isEmpty());
    }

    @Test
    void deleteRemovesTheSecret() {
        String key = store(new Credential("github.com", "https", "alice", "s3cr3t".toCharArray()));

        store.delete(key);

        assertTrue(store.find(key).isEmpty());
    }

    @Test
    void deleteIsIdempotentForAnUnknownKey() {
        // delete() runs before the ref row is removed and may be retried; an absent
        // credential must be a no-op, never an error.
        assertDoesNotThrow(() -> store.delete("configflow:does-not-exist"));
    }
}

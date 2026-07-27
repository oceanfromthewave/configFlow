package dev.configflow.infrastructure.credential;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.configflow.domain.credential.Credential;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class InMemoryCredentialStoreTest {

    @Test
    void keepsTheSecretEvenAfterTheCallerWipesItsArray() {
        InMemoryCredentialStore store = new InMemoryCredentialStore();
        char[] secret = "s3cr3t".toCharArray();

        String key = store.store(new Credential("github.com", "https", "alice", secret));
        // CredentialService wipes the caller's array right after store() returns
        // (see CredentialStore#store). A store that kept the reference would lose the
        // secret here — so it must have taken its own copy.
        Arrays.fill(secret, '\0');

        char[] kept = store.find(key).orElseThrow().secret();
        assertArrayEquals("s3cr3t".toCharArray(), kept);
    }

    @Test
    void findReturnsACopyTheCallerCanWipeWithoutTouchingTheStore() {
        InMemoryCredentialStore store = new InMemoryCredentialStore();
        String key = store.store(new Credential("github.com", "https", "alice", "s3cr3t".toCharArray()));

        // A caller wipes the secret it got back (the char[] discipline) — that must
        // not reach in and zero out the stored one. Mirrors the store() case.
        Arrays.fill(store.find(key).orElseThrow().secret(), '\0');

        char[] kept = store.find(key).orElseThrow().secret();
        assertArrayEquals("s3cr3t".toCharArray(), kept);
    }

    @Test
    void deleteRemovesTheSecret() {
        InMemoryCredentialStore store = new InMemoryCredentialStore();
        String key = store.store(new Credential("github.com", "https", null, "x".toCharArray()));

        store.delete(key);

        assertTrue(store.find(key).isEmpty());
    }
}

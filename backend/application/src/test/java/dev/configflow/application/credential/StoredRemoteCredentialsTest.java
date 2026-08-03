package dev.configflow.application.credential;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.configflow.domain.credential.Credential;
import dev.configflow.domain.credential.CredentialId;
import dev.configflow.domain.credential.CredentialRef;
import dev.configflow.domain.credential.CredentialRefStore;
import dev.configflow.domain.credential.CredentialStore;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The resolver's only job is to chain the two stores: the reference table says which
 * secret, the OS keychain hands it back. These pin down that chaining — including the
 * case that keeps the split honest, a reference with no secret left behind it.
 */
class StoredRemoteCredentialsTest {

    @Test
    void resolvesTheSecretStoredForAHost() {
        CredentialRef ref =
                CredentialRef.issue("github.com", "https", "alice", "key-1", Instant.now());
        StubRefStore refs = new StubRefStore(Optional.of(ref));
        StubSecretStore secrets = new StubSecretStore(
                Optional.of(new Credential("github.com", "https", "alice", "s3cr3t".toCharArray())));

        Optional<Credential> resolved =
                new StoredRemoteCredentials(refs, secrets).resolve("github.com", "https");

        assertTrue(resolved.isPresent());
        assertArrayEquals("s3cr3t".toCharArray(), resolved.orElseThrow().secret());
        // The keychain is asked by the opaque key from the row, not by host/protocol.
        assertEquals("key-1", secrets.askedFor);
    }

    @Test
    void isEmptyWhenNothingIsStoredForTheHost() {
        StubRefStore refs = new StubRefStore(Optional.empty());
        StubSecretStore secrets = new StubSecretStore(Optional.empty());

        assertTrue(new StoredRemoteCredentials(refs, secrets)
                .resolve("github.com", "https").isEmpty());
        // No reference means the keychain is never even consulted.
        assertNull(secrets.askedFor);
    }

    @Test
    void isEmptyWhenTheReferenceOutlivesItsSecret() {
        // The row points at a key the keychain no longer has (wiped out of band). A
        // reference on its own is worthless, so this must be empty — never a secretless
        // credential the transport would try to present.
        CredentialRef ref =
                CredentialRef.issue("github.com", "https", "alice", "key-gone", Instant.now());
        StubRefStore refs = new StubRefStore(Optional.of(ref));
        StubSecretStore secrets = new StubSecretStore(Optional.empty());

        assertTrue(new StoredRemoteCredentials(refs, secrets)
                .resolve("github.com", "https").isEmpty());
        assertEquals("key-gone", secrets.askedFor);
    }

    // --- resolveAll --------------------------------------------------------

    @Test
    void resolveAllOnlyReturnsKeysStoredForTheGivenHostAndProtocol() {
        CredentialRef githubKey = CredentialRef.issueSshkey("github.com", "git", "key-1", "pub-1", Instant.now());
        CredentialRef gitlabKey = CredentialRef.issueSshkey("gitlab.com", "git", "key-2", "pub-2", Instant.now());
        StubRefStore refs = new StubRefStore(Optional.empty(), List.of(githubKey, gitlabKey));
        StubSecretStore secrets = new StubSecretStore(Optional.empty());
        secrets.byKey.put("key-1", new Credential("github.com", "ssh", "git", "secret-1".toCharArray()));
        secrets.byKey.put("key-2", new Credential("gitlab.com", "ssh", "git", "secret-2".toCharArray()));

        List<Credential> resolved =
                new StoredRemoteCredentials(refs, secrets).resolveAll("github.com", "ssh");

        assertEquals(1, resolved.size());
        assertArrayEquals("secret-1".toCharArray(), resolved.get(0).secret());
    }

    @Test
    void resolveAllDropsARowWhoseSecretIsGone() {
        // Same rule as resolve(): a reference with no secret behind it is worthless, not
        // a half-built credential to offer.
        CredentialRef key = CredentialRef.issueSshkey("github.com", "git", "key-gone", "pub-1", Instant.now());
        StubRefStore refs = new StubRefStore(Optional.empty(), List.of(key));
        StubSecretStore secrets = new StubSecretStore(Optional.empty());

        assertTrue(new StoredRemoteCredentials(refs, secrets).resolveAll("github.com", "ssh").isEmpty());
    }

    private static final class StubRefStore implements CredentialRefStore {
        private final Optional<CredentialRef> match;
        private final List<CredentialRef> all;

        StubRefStore(Optional<CredentialRef> match) {
            this(match, List.of());
        }

        StubRefStore(Optional<CredentialRef> match, List<CredentialRef> all) {
            this.match = match;
            this.all = all;
        }

        @Override
        public Optional<CredentialRef> findFor(String host, String protocol) {
            return match;
        }

        @Override
        public void save(CredentialRef ref) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<CredentialRef> findById(CredentialId id) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<CredentialRef> findByTarget(String host, String protocol, String username) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<CredentialRef> findAll() {
            return all;
        }

        @Override
        public void delete(CredentialId id) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class StubSecretStore implements CredentialStore {
        private final Optional<Credential> secret;
        private final java.util.Map<String, Credential> byKey = new java.util.LinkedHashMap<>();
        private String askedFor;

        StubSecretStore(Optional<Credential> secret) {
            this.secret = secret;
        }

        @Override
        public Optional<Credential> find(String storeKey) {
            this.askedFor = storeKey;
            if (byKey.containsKey(storeKey)) {
                Credential c = byKey.get(storeKey);
                return Optional.of(new Credential(c.host(), c.protocol(), c.username(), c.secret().clone()));
            }
            // Mirror the real store contract: the secret is the caller's to wipe, so hand back
            // a copy rather than the array this stub keeps.
            return secret.map(c ->
                    new Credential(c.host(), c.protocol(), c.username(), c.secret().clone()));
        }

        @Override
        public String store(Credential credential) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void delete(String storeKey) {
            throw new UnsupportedOperationException();
        }
    }
}

package dev.configflow.application.credential;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.configflow.domain.credential.Credential;
import dev.configflow.domain.credential.CredentialId;
import dev.configflow.domain.credential.CredentialRef;
import dev.configflow.domain.credential.CredentialRefStore;
import dev.configflow.domain.credential.CredentialStore;
import dev.configflow.domain.credential.SshKeyFactory;
import dev.configflow.domain.credential.SshKeyPair;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Unit test of the two-store use case with in-memory fakes. The interesting behaviour is
 * ordering and what happens when the second store fails after the first has succeeded, so
 * both fakes append to a shared log the assertions read back.
 */
class CredentialServiceTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    /** Records every mutation so the test can assert the order across both stores. */
    private final List<String> events = new ArrayList<>();
    private final FakeCredentialStore secrets = new FakeCredentialStore(events);
    private final FakeCredentialRefStore refs = new FakeCredentialRefStore(events);
    private final FakeSshKeyFactory keys = new FakeSshKeyFactory();
    private final CredentialService service =
            new CredentialService(secrets, refs, keys, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void saveStoresTheSecretBeforeTheRow() {
        // The row has to name a key that already resolves; a crash in between must not
        // leave a credential the app lists but cannot use.
        CredentialRef saved = service.save("github.com", "https", "alice", "tok".toCharArray());

        assertEquals(List.of("store:mem:1", "refSave:" + saved.id().asString()), events);
        assertEquals("mem:1", saved.storeKey());
        assertEquals("alice", saved.username());
    }

    @Test
    void savePutsTheRealSecretInTheStore() {
        CredentialRef saved = service.save("github.com", "https", "alice", "tok".toCharArray());

        Credential stored = secrets.secrets.get(saved.storeKey());
        assertArrayEquals("tok".toCharArray(), stored.secret());
        assertEquals("alice", stored.username());
    }

    @Test
    void saveWipesTheCallersSecret() {
        char[] secret = "hunter2".toCharArray();
        service.save("github.com", "https", "alice", secret);

        // The whole point of char[] over String: the caller's copy is left blank.
        assertArrayEquals(new char[secret.length], secret);
    }

    @Test
    void saveRollsBackTheSecretWhenTheRowCannotBeSaved() {
        refs.failure = new RuntimeException("disk full");

        assertThrows(RuntimeException.class,
                () -> service.save("github.com", "https", "alice", "tok".toCharArray()));

        // With the row lost, nothing could ever find this secret again. Leaving it would
        // be a password in the OS store forever, unreachable and unremovable from here.
        assertTrue(secrets.secrets.isEmpty(), "the orphaned secret must be deleted");
        assertEquals(List.of("store:mem:1", "secretDelete:mem:1"), events);
    }

    @Test
    void saveReplacingKeepsTheIdAndDropsTheOldSecretOnlyAfterTheNewRowLands() {
        CredentialId original = service.save("github.com", "https", "alice", "old".toCharArray()).id();
        events.clear();

        CredentialRef rotated = service.save("github.com", "https", "alice", "new".toCharArray());

        // A rotated token replaces rather than adds: a second row would leave the lookup
        // choosing between a working secret and a dead one.
        assertEquals(original, rotated.id());
        assertEquals(1, refs.findAll().size());
        assertEquals(List.of(rotated.storeKey()), List.copyOf(secrets.secrets.keySet()));

        // The old secret is unreachable only after the row stops naming it; dropping it
        // earlier would open a window where the row points at a deleted key.
        assertEquals(
                List.of("store:mem:2", "refSave:" + rotated.id().asString(), "secretDelete:mem:1"),
                events);
    }

    @Test
    void saveTreatsADifferentlyCasedTargetAsTheSameCredential() {
        service.save("GitHub.com", "HTTPS", "alice", "old".toCharArray());
        service.save("github.com", "https", "alice", "new".toCharArray());

        // Host and protocol are case-insensitive. Untouched, these would be two rows and
        // one would shadow the other for whichever spelling a URL happened to use.
        assertEquals(1, refs.findAll().size());
    }

    @Test
    void saveTokenOnlyAuthStoresAnEmptyUsernameAndANullOne() {
        CredentialRef saved = service.save("github.com", "https", null, "pat".toCharArray());

        // The row keeps "" so SQLite's UNIQUE index still constrains it; the OS store gets
        // null, because a keychain entry has no empty username, it simply has none.
        assertEquals("", saved.username());
        assertNull(secrets.secrets.get(saved.storeKey()).username());
    }

    @Test
    void saveWithoutASecretIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> service.save("github.com", "https", "alice", new char[0]));
        assertThrows(IllegalArgumentException.class,
                () -> service.save("github.com", "https", "alice", null));
        assertTrue(events.isEmpty(), "a rejected save must touch neither store");
    }

    @Test
    void deleteRemovesTheSecretBeforeTheRow() {
        CredentialId id = service.save("github.com", "https", "alice", "tok".toCharArray()).id();
        events.clear();

        service.delete(id);

        // Row first would strand the secret in the keychain with nothing pointing at it.
        // This order leaves a row the user can see and delete again on failure.
        assertEquals(List.of("secretDelete:mem:1", "refDelete:" + id.asString()), events);
        assertTrue(secrets.secrets.isEmpty());
        assertTrue(refs.findAll().isEmpty());
    }

    @Test
    void deleteUnknownIdThrows() {
        assertThrows(NoSuchElementException.class, () -> service.delete(CredentialId.newId()));
    }

    // --- secretFor ---------------------------------------------------------

    @Test
    void secretForReturnsTheStoredSecretForARegisteredTarget() {
        service.save("api.anthropic.com", "https", "", "sk-ant-key".toCharArray());

        Optional<char[]> secret = service.secretFor("api.anthropic.com", "https", "");

        assertTrue(secret.isPresent());
        assertArrayEquals("sk-ant-key".toCharArray(), secret.get());
    }

    @Test
    void secretForNormalizesCaseAndWhitespaceLikeSaveDoes() {
        service.save("Api.Anthropic.com", "HTTPS", "", "sk-ant-key".toCharArray());

        // save() lowercases host/protocol and trims username; a lookup with different
        // casing or padding must resolve to the same row, not silently miss it.
        Optional<char[]> secret = service.secretFor(" api.anthropic.com ", " HTTPS ", " ");

        assertTrue(secret.isPresent());
        assertArrayEquals("sk-ant-key".toCharArray(), secret.get());
    }

    @Test
    void secretForIsEmptyWhenNothingIsRegisteredForTheTarget() {
        assertTrue(service.secretFor("api.anthropic.com", "https", "").isEmpty());
    }

    @Test
    void listReturnsEveryRegisteredCredential() {
        service.save("github.com", "https", "alice", "a".toCharArray());
        service.save("gitlab.com", "https", "bob", "b".toCharArray());

        assertEquals(2, service.list().size());
    }

    // --- createSshKey -----------------------------------------------------

    @Test
    void createSshKeyStoresThePrivateHalfAndReturnsThePublicHalf() {
        CredentialRef saved = service.createSshKey("github.com", "git", "git@laptop");

        assertEquals("ssh", saved.protocol());
        assertEquals("ssh-ed25519 AAAA... git@laptop", saved.publicKey());
        assertArrayEquals("private-pem".toCharArray(),
                secrets.secrets.get(saved.storeKey()).secret());
    }

    @Test
    void createSshKeyWipesTheGeneratedPrivateKey() {
        service.createSshKey("github.com", "git", "git@laptop");

        // The service's own copy must be blanked once the OS store has its own — the
        // same rule save() follows for a caller-supplied secret.
        assertArrayEquals(new char[keys.lastGenerated.length], keys.lastGenerated);
    }

    @Test
    void createSshKeyReplacesAnExistingKeyForTheSameHost() {
        CredentialId original = service.createSshKey("github.com", "git", "old").id();
        events.clear();

        CredentialRef rotated = service.createSshKey("github.com", "git", "new");

        assertEquals(original, rotated.id());
        assertEquals(1, refs.findAll().size());
    }

    /** In-memory secrets store; clones on the way in so wiping the caller's array leaves it intact. */
    private static final class FakeCredentialStore implements CredentialStore {
        private final Map<String, Credential> secrets = new LinkedHashMap<>();
        private final List<String> events;
        private int counter;

        FakeCredentialStore(List<String> events) {
            this.events = events;
        }

        @Override
        public String store(Credential credential) {
            String key = "mem:" + (++counter);
            // A real keychain copies the bytes before returning; mirror that so the
            // service wiping the original does not blank what we "persisted".
            secrets.put(key, new Credential(
                    credential.host(), credential.protocol(), credential.username(),
                    credential.secret().clone()));
            events.add("store:" + key);
            return key;
        }

        @Override
        public Optional<Credential> find(String storeKey) {
            return Optional.ofNullable(secrets.get(storeKey));
        }

        @Override
        public void delete(String storeKey) {
            secrets.remove(storeKey);
            events.add("secretDelete:" + storeKey);
        }
    }

    /** In-memory reference store; set {@link #failure} to make the next save blow up. */
    private static final class FakeCredentialRefStore implements CredentialRefStore {
        private final Map<CredentialId, CredentialRef> rows = new LinkedHashMap<>();
        private final List<String> events;
        private RuntimeException failure;

        FakeCredentialRefStore(List<String> events) {
            this.events = events;
        }

        @Override
        public void save(CredentialRef ref) {
            if (failure != null) {
                throw failure;
            }
            rows.put(ref.id(), ref);
            events.add("refSave:" + ref.id().asString());
        }

        @Override
        public Optional<CredentialRef> findById(CredentialId id) {
            return Optional.ofNullable(rows.get(id));
        }

        @Override
        public Optional<CredentialRef> findByTarget(String host, String protocol, String username) {
            return rows.values().stream()
                    .filter(r -> r.host().equals(host)
                            && r.protocol().equals(protocol)
                            && r.username().equals(username))
                    .findFirst();
        }

        @Override
        public Optional<CredentialRef> findFor(String host, String protocol) {
            return rows.values().stream()
                    .filter(r -> r.matches(host, protocol))
                    .max(Comparator.comparing(CredentialRef::createdAt));
        }

        @Override
        public List<CredentialRef> findAll() {
            return List.copyOf(rows.values());
        }

        @Override
        public void delete(CredentialId id) {
            rows.remove(id);
            events.add("refDelete:" + id.asString());
        }
    }

    /** Deterministic key generation so tests can assert on the exact material produced. */
    private static final class FakeSshKeyFactory implements SshKeyFactory {
        private char[] lastGenerated;

        @Override
        public SshKeyPair generate(String comment) {
            lastGenerated = "private-pem".toCharArray();
            return new SshKeyPair(lastGenerated, "ssh-ed25519 AAAA... " + comment);
        }
    }
}

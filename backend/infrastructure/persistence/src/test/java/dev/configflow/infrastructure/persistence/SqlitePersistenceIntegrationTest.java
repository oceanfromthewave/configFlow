package dev.configflow.infrastructure.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.configflow.domain.credential.CredentialId;
import dev.configflow.domain.credential.CredentialRef;
import dev.configflow.domain.operation.Operation;
import dev.configflow.domain.operation.OperationFailure;
import dev.configflow.domain.operation.OperationFailures;
import dev.configflow.domain.operation.OperationId;
import dev.configflow.domain.operation.OperationState;
import dev.configflow.domain.operation.OperationType;
import dev.configflow.domain.repository.Repository;
import dev.configflow.domain.vcs.model.VcsType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Integration test against a real temp-file SQLite database: verifies that the
 * Flyway migration runs and that the adapters implement the ports correctly.
 */
class SqlitePersistenceIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    @TempDir
    Path tempDir;

    private SqliteDatabase database;

    @BeforeEach
    void setUp() {
        database = new SqliteDatabase(tempDir.resolve("configflow-test.db"));
        database.migrate();
    }

    @Test
    void migrationCreatesDatabaseFileAndIsIdempotent() {
        assertTrue(Files.exists(database.path()));
        database.migrate(); // second run must be a no-op, not a failure
    }

    @Test
    void repositoryCrudRoundTrips() {
        SqliteRepositoryStore store = new SqliteRepositoryStore(database);
        Repository repo = Repository.register(
                "configFlow", Path.of("C:/dev/configFlow"), "https://example.com/configFlow.git",
                VcsType.GIT, Instant.parse("2026-07-02T00:00:00Z"));

        // create + read
        store.save(repo);
        assertEquals(Optional.of(repo), store.findById(repo.id()));
        assertEquals(Optional.of(repo), store.findByLocalPath(repo.localPath()));

        // update (upsert)
        Repository updated = repo.withFavorite(true)
                .withGroupName("work")
                .opened(Instant.parse("2026-07-02T12:00:00Z"));
        store.save(updated);
        assertEquals(Optional.of(updated), store.findById(repo.id()));
        assertEquals(List.of(updated), store.findAll());

        // delete
        store.delete(repo.id());
        assertTrue(store.findById(repo.id()).isEmpty());
        assertTrue(store.findAll().isEmpty());
    }

    @Test
    void findAllOrdersByLastOpenedDescending() {
        SqliteRepositoryStore store = new SqliteRepositoryStore(database);
        Instant base = Instant.parse("2026-07-01T00:00:00Z");
        Repository older = Repository.register("a", Path.of("C:/repos/a"), null, VcsType.GIT, base)
                .opened(base.plusSeconds(60));
        Repository newer = Repository.register("b", Path.of("C:/repos/b"), null, VcsType.SVN, base)
                .opened(base.plusSeconds(3600));
        Repository neverOpened = Repository.register("c", Path.of("C:/repos/c"), null, VcsType.GIT, base);

        store.save(older);
        store.save(newer);
        store.save(neverOpened);

        assertEquals(List.of(newer, older, neverOpened), store.findAll());
    }

    @Test
    void settingsRoundTrip() {
        SqliteSettingsStore store = new SqliteSettingsStore(database);

        assertTrue(store.get("theme").isEmpty());
        store.put("theme", "dark");
        store.put("language", "ko");
        store.put("theme", "light"); // upsert

        assertEquals(Optional.of("light"), store.get("theme"));
        assertEquals(2, store.findAll().size());

        store.remove("language");
        assertTrue(store.get("language").isEmpty());
    }

    @Test
    void operationHistoryRoundTripsAndCascadesOnRepositoryDelete() {
        SqliteRepositoryStore repositories = new SqliteRepositoryStore(database);
        SqliteOperationHistoryStore operations = new SqliteOperationHistoryStore(database);

        Repository repo = Repository.register(
                "x", Path.of("C:/repos/x"), null, VcsType.GIT, Instant.parse("2026-07-02T00:00:00Z"));
        repositories.save(repo);

        Operation finished = new Operation(
                Operation.queued(repo.id(), OperationType.FETCH).id(),
                repo.id(),
                OperationType.FETCH,
                OperationState.SUCCEEDED,
                null,
                Instant.parse("2026-07-02T10:00:00Z"),
                Instant.parse("2026-07-02T10:00:05Z"),
                null,
                List.of("git fetch origin", "done"));
        operations.save(finished);

        assertEquals(Optional.of(finished), operations.findById(finished.id()));
        assertEquals(List.of(finished), operations.findRecent(repo.id(), 10));

        // ON DELETE CASCADE (requires enforced foreign keys)
        repositories.delete(repo.id());
        assertFalse(operations.findById(finished.id()).isPresent());
    }

    @Test
    void anArchivedFailureKeepsItsName() {
        SqliteOperationHistoryStore operations = new SqliteOperationHistoryStore(database);

        // Without the code, the same auth failure looks like one thing while it is live
        // on the event stream and like another once the panel reads it back.
        Operation failed = failedOperation(new OperationFailure(
                OperationFailures.VCS_AUTH_REQUIRED,
                "Authentication required for https://github.com",
                Map.of("host", "github.com", "protocol", "https")));
        operations.save(failed);

        OperationFailure stored = operations.findById(failed.id()).orElseThrow().failure();
        assertEquals(OperationFailures.VCS_AUTH_REQUIRED, stored.code());
        assertEquals("Authentication required for https://github.com", stored.message());
    }

    @Test
    void anArchivedFailureDropsItsContextRatherThanPretendingToBeRetryable() {
        SqliteOperationHistoryStore operations = new SqliteOperationHistoryStore(database);

        Operation failed = failedOperation(new OperationFailure(
                OperationFailures.VCS_AUTH_REQUIRED, "nope",
                Map.of("host", "github.com", "protocol", "https")));
        operations.save(failed);

        // The context answers "how do I retry this", and this table cannot: it keeps no
        // remote name, no pull strategy, no clone URL. Storing half an answer would put a
        // retry button on a row that has nothing to retry with.
        assertTrue(operations.findById(failed.id()).orElseThrow().failure().context().isEmpty());
    }

    @Test
    void aRowArchivedBeforeCodesExistedIsNotGivenOne() throws Exception {
        SqliteOperationHistoryStore operations = new SqliteOperationHistoryStore(database);
        Operation failed = failedOperation(
                OperationFailure.of(OperationFailures.VCS_NETWORK_ERROR, "unreachable"));
        operations.save(failed);

        // What an older build left behind: a message and no name.
        try (var con = database.openConnection();
                var ps = con.prepareStatement(
                        "UPDATE operation_history SET error_code = NULL WHERE id = ?")) {
            ps.setString(1, failed.id().asString());
            ps.executeUpdate();
        }

        OperationFailure stored = operations.findById(failed.id()).orElseThrow().failure();
        assertEquals(OperationFailures.UNKNOWN, stored.code(),
                "calling it INTERNAL_ERROR would be a claim about a cause nobody recorded");
        assertEquals("unreachable", stored.message());
    }

    @Test
    void anOperationThatSucceededHasNoFailureToReadBack() {
        SqliteOperationHistoryStore operations = new SqliteOperationHistoryStore(database);
        Operation succeeded = new Operation(
                OperationId.newId(), null, OperationType.FETCH, OperationState.SUCCEEDED,
                null, Instant.parse("2026-07-02T10:00:00Z"),
                Instant.parse("2026-07-02T10:00:05Z"), null, List.of());
        operations.save(succeeded);

        assertNull(operations.findById(succeeded.id()).orElseThrow().failure());
    }

    @Test
    void credentialRefCrudRoundTrips() {
        SqliteCredentialRefStore store = new SqliteCredentialRefStore(database);
        CredentialRef alice = CredentialRef.issue("github.com", "https", "alice", "key-1", NOW);
        CredentialRef bob = CredentialRef.issue("gitlab.com", "https", "bob", "key-2", NOW.plusSeconds(60));

        store.save(alice);
        store.save(bob);

        assertEquals(Optional.of(alice), store.findById(alice.id()));
        assertEquals(Optional.of(alice), store.findByTarget("github.com", "https", "alice"));
        // Newest first.
        assertEquals(List.of(bob, alice), store.findAll());

        store.delete(alice.id());
        assertTrue(store.findById(alice.id()).isEmpty());
        assertEquals(List.of(bob), store.findAll());
    }

    @Test
    void credentialUpsertKeepsTheOriginalCreationTime() {
        SqliteCredentialRefStore store = new SqliteCredentialRefStore(database);
        CredentialRef first = CredentialRef.issue("github.com", "https", "alice", "key-1", NOW);
        store.save(first);

        // Same id, rotated token: an update, not a fresh registration.
        CredentialRef rotated = new CredentialRef(
                first.id(), "github.com", "https", "alice", "key-2", null, NOW.plusSeconds(3600));
        store.save(rotated);

        CredentialRef stored = store.findById(first.id()).orElseThrow();
        assertEquals("key-2", stored.storeKey());
        assertEquals(NOW, stored.createdAt(), "an update is not a new issue");
        assertEquals(1, store.findAll().size());
    }

    @Test
    void findByTargetMatchesRegardlessOfHostCasing() {
        SqliteCredentialRefStore store = new SqliteCredentialRefStore(database);
        store.save(CredentialRef.issue("github.com", "https", "alice", "key-1", NOW));

        // The row was normalised to lower case on the way in; the lookup has to normalise
        // too, or a caller spelling it "GitHub.com" would miss its own credential.
        assertTrue(store.findByTarget("GitHub.com", "HTTPS", "alice").isPresent());
    }

    @Test
    void findForPicksTheNewestCredentialForTheHost() {
        SqliteCredentialRefStore store = new SqliteCredentialRefStore(database);
        // Two accounts on one host. findFor is username-agnostic — it is what a fetch asks,
        // knowing only the URL — and takes the newest, so a rotated token beats the row it
        // replaced instead of the stale one shadowing it.
        CredentialRef older = CredentialRef.issue("github.com", "https", "alice", "key-old", NOW);
        CredentialRef newer =
                CredentialRef.issue("github.com", "https", "bob", "key-new", NOW.plusSeconds(60));
        store.save(older);
        store.save(newer);

        assertEquals(Optional.of(newer), store.findFor("github.com", "https"));
    }

    @Test
    void publicKeyRoundTripsForAnSshCredential() {
        SqliteCredentialRefStore store = new SqliteCredentialRefStore(database);
        CredentialRef sshKey = CredentialRef.issueSshkey("github.com", "git", "key-1", "ssh-ed25519 AAAA... git@laptop", NOW);
        store.save(sshKey);

        CredentialRef stored = store.findById(sshKey.id()).orElseThrow();
        assertEquals("ssh-ed25519 AAAA... git@laptop", stored.publicKey());
    }

    @Test
    void publicKeyIsNullForNonSshCredentials() {
        SqliteCredentialRefStore store = new SqliteCredentialRefStore(database);
        store.save(CredentialRef.issue("github.com", "https", "alice", "key-1", NOW));

        CredentialRef stored = store.findAll().get(0);
        assertNull(stored.publicKey());
    }

    @Test
    void findForOrdersByActualTimeNotIsoStringLength() {
        SqliteCredentialRefStore store = new SqliteCredentialRefStore(database);
        // Same second, but one timestamp has a fractional part and the other does not.
        // Lexicographically "…00Z" sorts after "…00.500Z" (since '.' < 'Z'), so a plain string
        // ORDER BY would wrongly pick the older whole-second row as the newest.
        CredentialRef older = CredentialRef.issue(
                "github.com", "https", "alice", "key-old", Instant.parse("2026-07-02T10:00:00Z"));
        CredentialRef newer = CredentialRef.issue(
                "github.com", "https", "bob", "key-new", Instant.parse("2026-07-02T10:00:00.500Z"));
        store.save(older);
        store.save(newer);

        assertEquals(Optional.of(newer), store.findFor("github.com", "https"));
    }

    @Test
    void findForNormalisesHostAndProtocolCasing() {
        SqliteCredentialRefStore store = new SqliteCredentialRefStore(database);
        store.save(CredentialRef.issue("github.com", "https", "alice", "key-1", NOW));

        // A remote URL might be spelled "GitHub.com"; the row was lowercased on the way in,
        // so the lookup lowercases too or it finds nothing.
        assertTrue(store.findFor("GitHub.com", "HTTPS").isPresent());
    }

    @Test
    void findForIsEmptyWhenNoCredentialMatchesTheHost() {
        SqliteCredentialRefStore store = new SqliteCredentialRefStore(database);
        store.save(CredentialRef.issue("github.com", "https", "alice", "key-1", NOW));

        // Same host but the wrong protocol, and an unknown host, must both miss.
        assertTrue(store.findFor("github.com", "ssh").isEmpty());
        assertTrue(store.findFor("gitlab.com", "https").isEmpty());
    }

    @Test
    void tokenOnlyCredentialsAreStillConstrainedByTheUniqueIndex() {
        SqliteCredentialRefStore store = new SqliteCredentialRefStore(database);
        // username null becomes "" in CredentialRef precisely so SQLite's UNIQUE index
        // engages: stored as NULL it would count every token-only row as distinct and let
        // duplicates for one host pile up — the common case for a GitHub PAT.
        store.save(CredentialRef.issue("github.com", "https", null, "key-1", NOW));

        CredentialRef duplicate = CredentialRef.issue("github.com", "https", null, "key-2", NOW);
        assertThrows(PersistenceException.class, () -> store.save(duplicate));
    }

    /** Repository-less on purpose: no row in {@code repository} to reference. */
    private static Operation failedOperation(OperationFailure failure) {
        return new Operation(
                OperationId.newId(), null, OperationType.FETCH, OperationState.FAILED,
                null, Instant.parse("2026-07-02T10:00:00Z"),
                Instant.parse("2026-07-02T10:00:05Z"), failure, List.of());
    }
}

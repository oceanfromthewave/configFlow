package dev.configflow.infrastructure.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    /** Repository-less on purpose: no row in {@code repository} to reference. */
    private static Operation failedOperation(OperationFailure failure) {
        return new Operation(
                OperationId.newId(), null, OperationType.FETCH, OperationState.FAILED,
                null, Instant.parse("2026-07-02T10:00:00Z"),
                Instant.parse("2026-07-02T10:00:05Z"), failure, List.of());
    }
}

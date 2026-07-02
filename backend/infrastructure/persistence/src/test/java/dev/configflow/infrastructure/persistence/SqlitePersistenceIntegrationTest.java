package dev.configflow.infrastructure.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.configflow.domain.operation.Operation;
import dev.configflow.domain.operation.OperationState;
import dev.configflow.domain.operation.OperationType;
import dev.configflow.domain.repository.Repository;
import dev.configflow.domain.vcs.model.VcsType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
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
}

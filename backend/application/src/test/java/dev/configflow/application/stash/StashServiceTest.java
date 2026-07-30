package dev.configflow.application.stash;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.configflow.application.operation.OperationQueue;
import dev.configflow.application.vcs.DefaultVcsProviderRegistry;
import dev.configflow.application.vcs.VcsAccess;
import dev.configflow.domain.operation.Operation;
import dev.configflow.domain.operation.OperationEvents;
import dev.configflow.domain.operation.OperationHistoryStore;
import dev.configflow.domain.operation.OperationId;
import dev.configflow.domain.operation.OperationProgress;
import dev.configflow.domain.operation.OperationType;
import dev.configflow.domain.repository.Repository;
import dev.configflow.domain.repository.RepositoryId;
import dev.configflow.domain.repository.RepositoryStore;
import dev.configflow.domain.vcs.capability.VcsCapability;
import dev.configflow.domain.vcs.model.RepositoryHandle;
import dev.configflow.domain.vcs.model.StashEntry;
import dev.configflow.domain.vcs.model.VcsType;
import dev.configflow.domain.vcs.port.StashOperations;
import dev.configflow.domain.vcs.port.VcsProvider;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StashServiceTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    private final InMemoryRepositoryStore store = new InMemoryRepositoryStore();
    private final FakeStashProvider provider = new FakeStashProvider();
    private final RecordingEvents events = new RecordingEvents();
    private final NoHistory history = new NoHistory();

    @TempDir
    Path repoDir;

    private StashService service() {
        VcsAccess access = new VcsAccess(store, new DefaultVcsProviderRegistry(List.of(provider)));
        OperationQueue queue = new OperationQueue(
                history, OperationEvents.noop(), Clock.fixed(NOW, ZoneOffset.UTC), Runnable::run);
        return new StashService(access, queue, events);
    }

    private RepositoryId register() {
        Repository repository = Repository.register(
                "demo", repoDir.toAbsolutePath().normalize(), null, VcsType.GIT, NOW);
        store.save(repository);
        return repository.id();
    }

    @Test
    void drop_publishesWorkingTreeChanged() {
        StashService service = service();
        RepositoryId id = register();

        service.drop(id, 0);

        assertEquals(List.of(id), events.workingTreeChanged);
        assertEquals(List.of("drop:0"), provider.calls);
    }

    @Test
    void apply_pop_drop_rejectNegativeIndexWithoutQueueingAnything() {
        StashService service = service();
        RepositoryId id = register();

        assertThrows(IllegalArgumentException.class, () -> service.apply(id, -1));
        assertThrows(IllegalArgumentException.class, () -> service.pop(id, -1));
        assertThrows(IllegalArgumentException.class, () -> service.drop(id, -1));
        assertTrue(provider.calls.isEmpty());
        assertTrue(history.saved.isEmpty(), "a rejected request must not become an operation");
    }

    // --- fakes -----------------------------------------------------------

    private static final class FakeStashProvider implements VcsProvider, StashOperations {

        private final List<String> calls = new ArrayList<>();

        @Override
        public VcsType type() {
            return VcsType.GIT;
        }

        @Override
        public Set<VcsCapability> capabilities() {
            return Set.of(VcsCapability.STASH);
        }

        @Override
        public boolean detect(Path localPath) {
            return true;
        }

        @Override
        public RepositoryHandle open(Path localPath) {
            return new RepositoryHandle(localPath, VcsType.GIT);
        }

        @Override
        public List<StashEntry> list(RepositoryHandle repo) {
            return List.of();
        }

        @Override
        public void save(RepositoryHandle repo, String message, boolean includeUntracked) {
            calls.add("save:" + message + ":" + includeUntracked);
        }

        @Override
        public void apply(RepositoryHandle repo, int index) {
            calls.add("apply:" + index);
        }

        @Override
        public void pop(RepositoryHandle repo, int index) {
            calls.add("pop:" + index);
        }

        @Override
        public void drop(RepositoryHandle repo, int index) {
            calls.add("drop:" + index);
        }
    }

    private static final class RecordingEvents implements OperationEvents {

        private final List<RepositoryId> workingTreeChanged = new ArrayList<>();

        @Override
        public void progress(OperationId id, OperationProgress value) {
        }

        @Override
        public void completed(Operation operation) {
        }

        @Override
        public void consoleLine(
                RepositoryId repositoryId, OperationId id, String line, String level) {
        }

        @Override
        public void refsChanged(RepositoryId repositoryId) {
        }

        @Override
        public void workingTreeChanged(RepositoryId repositoryId) {
            workingTreeChanged.add(repositoryId);
        }

        @Override
        public void repositoryRegistered(RepositoryId repositoryId) {
        }
    }

    private static final class NoHistory implements OperationHistoryStore {

        private final Map<OperationId, Operation> saved = new LinkedHashMap<>();

        @Override
        public void save(Operation operation) {
            saved.put(operation.id(), operation);
        }

        @Override
        public Optional<Operation> findById(OperationId id) {
            return Optional.ofNullable(saved.get(id));
        }

        @Override
        public List<Operation> findRecent(RepositoryId repositoryId, int limit) {
            return List.of();
        }
    }

    private static final class InMemoryRepositoryStore implements RepositoryStore {

        private final Map<RepositoryId, Repository> byId = new LinkedHashMap<>();

        @Override
        public void save(Repository repository) {
            byId.put(repository.id(), repository);
        }

        @Override
        public Optional<Repository> findById(RepositoryId id) {
            return Optional.ofNullable(byId.get(id));
        }

        @Override
        public Optional<Repository> findByLocalPath(Path localPath) {
            return byId.values().stream().filter(r -> r.localPath().equals(localPath)).findFirst();
        }

        @Override
        public List<Repository> findAll() {
            return new ArrayList<>(byId.values());
        }

        @Override
        public void delete(RepositoryId id) {
            byId.remove(id);
        }
    }
}

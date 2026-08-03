package dev.configflow.application.reset;

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
import dev.configflow.domain.vcs.exception.VcsPreconditionException;
import dev.configflow.domain.vcs.model.RepositoryHandle;
import dev.configflow.domain.vcs.model.ResetMode;
import dev.configflow.domain.vcs.model.RevisionId;
import dev.configflow.domain.vcs.model.VcsType;
import dev.configflow.domain.vcs.port.ResetOperations;
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

class ResetServiceTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    private final InMemoryRepositoryStore store = new InMemoryRepositoryStore();
    private final FakeResetProvider provider = new FakeResetProvider();
    private final RecordingEvents events = new RecordingEvents();
    private final NoHistory history = new NoHistory();

    @TempDir
    Path repoDir;

    private ResetService service() {
        return service(provider);
    }

    private ResetService service(FakeResetProvider providerToUse) {
        VcsAccess access = new VcsAccess(store, new DefaultVcsProviderRegistry(List.of(providerToUse)));
        OperationQueue queue = new OperationQueue(
                history, OperationEvents.noop(), Clock.fixed(NOW, ZoneOffset.UTC), Runnable::run);
        return new ResetService(access, queue, events);
    }

    private RepositoryId register() {
        Repository repository = Repository.register(
                "demo", repoDir.toAbsolutePath().normalize(), null, VcsType.GIT, NOW);
        store.save(repository);
        return repository.id();
    }

    @Test
    void reset_passesTargetAndModeAndPublishesBothChangeEvents() {
        RepositoryId id = register();

        Operation operation = service().reset(id, "abc123", "hard");

        assertEquals(OperationType.RESET, operation.type());
        assertEquals(new RevisionId("abc123"), provider.target);
        assertEquals(ResetMode.HARD, provider.mode);
        assertEquals(List.of(id), events.refsChanged);
        assertEquals(List.of(id), events.workingTreeChanged);
    }

    @Test
    void reset_defaultsModeToMixedWhenOmitted() {
        RepositoryId id = register();

        service().reset(id, "abc123", null);

        assertEquals(ResetMode.MIXED, provider.mode);
    }

    @Test
    void reset_acceptsAnUppercaseMode() {
        RepositoryId id = register();

        service().reset(id, "abc123", "SOFT");

        assertEquals(ResetMode.SOFT, provider.mode);
    }

    @Test
    void reset_publishesBothChangeEventsEvenWhenTheResetFails() {
        RepositoryId id = register();

        service(new FakeResetProvider(true)).reset(id, "abc123", "hard");

        assertEquals(List.of(id), events.refsChanged);
        assertEquals(List.of(id), events.workingTreeChanged);
    }

    @Test
    void reset_trimsTheTarget() {
        RepositoryId id = register();

        service().reset(id, "  abc123  ", "hard");

        assertEquals(new RevisionId("abc123"), provider.target);
    }

    @Test
    void reset_rejectsABlankTargetWithoutQueueingAnything() {
        RepositoryId id = register();

        assertThrows(IllegalArgumentException.class, () -> service().reset(id, "  ", "hard"));
        assertTrue(history.saved.isEmpty(), "a rejected request must not become an operation");
    }

    @Test
    void reset_rejectsAnInvalidMode() {
        RepositoryId id = register();

        assertThrows(IllegalArgumentException.class, () -> service().reset(id, "abc123", "bogus"));
        assertTrue(history.saved.isEmpty());
    }

    // --- fakes -----------------------------------------------------------

    private static final class FakeResetProvider implements VcsProvider, ResetOperations {

        private RevisionId target;
        private ResetMode mode;
        private final boolean failing;

        FakeResetProvider() {
            this(false);
        }

        FakeResetProvider(boolean failing) {
            this.failing = failing;
        }

        @Override
        public VcsType type() {
            return VcsType.GIT;
        }

        @Override
        public Set<VcsCapability> capabilities() {
            return Set.of();
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
        public void reset(RepositoryHandle repo, RevisionId target, ResetMode mode) {
            this.target = target;
            this.mode = mode;
            if (failing) {
                throw new VcsPreconditionException("reset failed");
            }
        }
    }

    private static final class RecordingEvents implements OperationEvents {

        private final List<RepositoryId> refsChanged = new ArrayList<>();
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
            refsChanged.add(repositoryId);
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

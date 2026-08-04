package dev.configflow.application.rebase;

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
import dev.configflow.domain.vcs.model.VcsType;
import dev.configflow.domain.vcs.port.RebaseOperations;
import dev.configflow.domain.vcs.port.VcsProvider;
import dev.configflow.domain.vcs.exception.MergeConflictException;
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

class RebaseServiceTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    private final InMemoryRepositoryStore store = new InMemoryRepositoryStore();
    private final FakeRebaseProvider provider = new FakeRebaseProvider();
    private final RecordingEvents events = new RecordingEvents();
    private final NoHistory history = new NoHistory();

    @TempDir
    Path repoDir;

    private RebaseService service() {
        VcsAccess access = new VcsAccess(store, new DefaultVcsProviderRegistry(List.of(provider)));
        OperationQueue queue = new OperationQueue(
                history, OperationEvents.noop(), Clock.fixed(NOW, ZoneOffset.UTC), Runnable::run);
        return new RebaseService(access, queue, events);
    }

    private RepositoryId register() {
        Repository repository = Repository.register(
                "demo", repoDir.toAbsolutePath().normalize(), null, VcsType.GIT, NOW);
        store.save(repository);
        return repository.id();
    }

    @Test
    void start_passesUpstreamAndPublishesBothChangeEvents() {
        RebaseService service = service();
        RepositoryId id = register();

        Operation operation = service.start(id, "main");

        assertEquals(OperationType.REBASE, operation.type());
        assertEquals(List.of("start:main"), provider.calls);
        // A rebase moves HEAD *and* rewrites the working tree, unlike a tag.
        assertEquals(List.of(id), events.refsChanged);
        assertEquals(List.of(id), events.workingTreeChanged);
    }

    @Test
    void start_publishesBothChangeEventsEvenWhenTheRebaseStopsOnConflicts() {
        // 충돌로 멈춘 리베이스는 그 지점까지의 커밋을 이미 재적용해 놓고 워킹 트리를 충돌 상태로
        // 남긴다. 실패한 경우가 오히려 화면 갱신이 필요한 경우다.
        provider.failWith = new MergeConflictException(List.of(Path.of("app.txt")));
        RebaseService service = service();
        RepositoryId id = register();

        service.start(id, "main");

        assertEquals(List.of(id), events.refsChanged);
        assertEquals(List.of(id), events.workingTreeChanged);
    }

    @Test
    void start_trimsTheUpstream() {
        RebaseService service = service();
        RepositoryId id = register();

        service.start(id, "  main  ");

        assertEquals(List.of("start:main"), provider.calls);
    }

    @Test
    void start_rejectsBlankUpstreamWithoutQueueingAnything() {
        RebaseService service = service();
        RepositoryId id = register();

        assertThrows(IllegalArgumentException.class, () -> service.start(id, "  "));
        assertTrue(provider.calls.isEmpty());
        assertTrue(history.saved.isEmpty(), "a rejected request must not become an operation");
    }

    @Test
    void start_rejectsNullUpstream() {
        RebaseService service = service();
        RepositoryId id = register();

        assertThrows(IllegalArgumentException.class, () -> service.start(id, null));
        assertTrue(provider.calls.isEmpty());
    }

    @Test
    void continueRebase_delegatesAndPublishesBothChangeEvents() {
        RebaseService service = service();
        RepositoryId id = register();

        Operation operation = service.continueRebase(id);

        assertEquals(OperationType.REBASE, operation.type());
        assertEquals(List.of("continue"), provider.calls);
        assertEquals(List.of(id), events.refsChanged);
        assertEquals(List.of(id), events.workingTreeChanged);
    }

    @Test
    void abort_delegatesAndPublishesBothChangeEvents() {
        RebaseService service = service();
        RepositoryId id = register();

        service.abort(id);

        // Abort restores the pre-rebase state, which is just as much of a change.
        assertEquals(List.of("abort"), provider.calls);
        assertEquals(List.of(id), events.refsChanged);
        assertEquals(List.of(id), events.workingTreeChanged);
    }

    @Test
    void skip_delegatesAndPublishesBothChangeEvents() {
        RebaseService service = service();
        RepositoryId id = register();

        service.skip(id);

        assertEquals(List.of("skip"), provider.calls);
        assertEquals(List.of(id), events.refsChanged);
        assertEquals(List.of(id), events.workingTreeChanged);
    }

    // --- fakes -----------------------------------------------------------

    private static final class FakeRebaseProvider implements VcsProvider, RebaseOperations {

        private final List<String> calls = new ArrayList<>();
        private RuntimeException failWith;

        @Override
        public VcsType type() {
            return VcsType.GIT;
        }

        @Override
        public Set<VcsCapability> capabilities() {
            return Set.of(VcsCapability.REBASE);
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
        public void start(RepositoryHandle repo, String upstream) {
            calls.add("start:" + upstream);
            if (failWith != null) {
                throw failWith;
            }
        }

        @Override
        public void continueRebase(RepositoryHandle repo) {
            calls.add("continue");
        }

        @Override
        public void abort(RepositoryHandle repo) {
            calls.add("abort");
        }

        @Override
        public void skip(RepositoryHandle repo) {
            calls.add("skip");
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

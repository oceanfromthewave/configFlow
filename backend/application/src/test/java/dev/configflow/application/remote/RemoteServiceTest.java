package dev.configflow.application.remote;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import dev.configflow.domain.operation.OperationState;
import dev.configflow.domain.operation.OperationType;
import dev.configflow.domain.repository.Repository;
import dev.configflow.domain.repository.RepositoryId;
import dev.configflow.domain.repository.RepositoryStore;
import dev.configflow.domain.vcs.capability.VcsCapability;
import dev.configflow.domain.vcs.exception.VcsPreconditionException;
import dev.configflow.domain.vcs.model.FetchRequest;
import dev.configflow.domain.vcs.model.PullRequest;
import dev.configflow.domain.vcs.model.PushRequest;
import dev.configflow.domain.vcs.model.RepositoryHandle;
import dev.configflow.domain.vcs.model.VcsType;
import dev.configflow.domain.vcs.port.OperationMonitor;
import dev.configflow.domain.vcs.port.RemoteSyncOperations;
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
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RemoteServiceTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    private final InMemoryRepositoryStore store = new InMemoryRepositoryStore();
    private final FakeRemoteProvider provider = new FakeRemoteProvider();
    private final RecordingEvents events = new RecordingEvents();
    private final InMemoryHistory history = new InMemoryHistory();

    @TempDir
    Path repoDir;

    private RemoteService serviceFor(VcsProvider... providers) {
        VcsAccess access = new VcsAccess(store, new DefaultVcsProviderRegistry(List.of(providers)));
        OperationQueue queue = new OperationQueue(
                history, events, Clock.fixed(NOW, ZoneOffset.UTC), Runnable::run);
        return new RemoteService(access, queue, events);
    }

    private RepositoryId register() {
        Repository repository = Repository.register(
                "demo", repoDir.toAbsolutePath().normalize(), null, VcsType.GIT, NOW);
        store.save(repository);
        return repository.id();
    }

    // --- fetch -----------------------------------------------------------

    @Test
    void fetch_forwardsTheRemoteAndPruneFlag() {
        RemoteService service = serviceFor(provider);
        RepositoryId id = register();

        Operation operation = service.fetch(id, "upstream", true);

        assertEquals(OperationType.FETCH, operation.type());
        assertEquals("upstream", provider.lastFetch.remote());
        assertTrue(provider.lastFetch.prune());
    }

    @Test
    void fetch_treatsABlankRemoteAsTheDefault() {
        RemoteService service = serviceFor(provider);
        RepositoryId id = register();

        service.fetch(id, "   ", false);

        // null means "whatever the repository calls its default", not a remote named "".
        assertEquals(null, provider.lastFetch.remote());
    }

    @Test
    void fetch_announcesThatRefsMovedButNotTheWorkingTree() {
        RemoteService service = serviceFor(provider);
        RepositoryId id = register();

        service.fetch(id, null, false);

        assertEquals(List.of(id), events.refsChanged);
        assertTrue(events.workingTreeChanged.isEmpty(),
                "fetch does not touch the working tree, so nothing should refetch it");
    }

    // --- pull ------------------------------------------------------------

    @Test
    void pull_forwardsTheStrategy() {
        RemoteService service = serviceFor(provider);
        RepositoryId id = register();

        service.pull(id, null, PullRequest.Strategy.REBASE);

        assertEquals(PullRequest.Strategy.REBASE, provider.lastPull.strategy());
    }

    @Test
    void pull_defaultsToMergeWhenNoStrategyIsGiven() {
        RemoteService service = serviceFor(provider);
        RepositoryId id = register();

        service.pull(id, null, null);

        assertEquals(PullRequest.Strategy.MERGE, provider.lastPull.strategy());
    }

    @Test
    void pull_announcesBothRefsAndWorkingTree() {
        RemoteService service = serviceFor(provider);
        RepositoryId id = register();

        service.pull(id, null, PullRequest.Strategy.MERGE);

        assertEquals(List.of(id), events.refsChanged);
        assertEquals(List.of(id), events.workingTreeChanged);
    }

    @Test
    void pull_announcesBothEvenWhenTheIntegrationConflicts() {
        // pull은 ref를 먼저 받아온 뒤 통합한다. 통합이 충돌해도 ref는 이미 움직였고 워킹 트리는
        // 충돌 상태로 남으므로, 실패한 경우가 오히려 갱신이 필요한 경우다.
        provider.failWith = new MergeConflictException(List.of(Path.of("app.txt")));
        RemoteService service = serviceFor(provider);
        RepositoryId id = register();

        service.pull(id, null, PullRequest.Strategy.MERGE);

        assertEquals(List.of(id), events.refsChanged);
        assertEquals(List.of(id), events.workingTreeChanged);
    }

    // --- push ------------------------------------------------------------

    @Test
    void push_forwardsEveryFlag() {
        RemoteService service = serviceFor(provider);
        RepositoryId id = register();

        service.push(id, "upstream", true, true);

        assertEquals("upstream", provider.lastPush.remote());
        assertTrue(provider.lastPush.forceWithLease());
        assertTrue(provider.lastPush.pushTags());
    }

    @Test
    void push_rejectionIsRecordedOnTheOperationRatherThanThrown() {
        provider.failWith = new VcsPreconditionException("REJECTED_NONFASTFORWARD");
        RemoteService service = serviceFor(provider);
        RepositoryId id = register();

        Operation operation = service.push(id, null, false, false);

        // Accepted work reports its outcome through the operation, not the submit call.
        assertEquals(OperationState.FAILED, history.saved.get(operation.id()).state());
        assertTrue(events.refsChanged.isEmpty(),
                "a push that never landed must not claim the refs moved");
    }

    // --- shared behaviour ------------------------------------------------

    @Test
    void progressFromTheEngineReachesTheOperation() {
        provider.reportProgress = new OperationProgress(42, "Receiving objects", "42/100");
        RemoteService service = serviceFor(provider);
        RepositoryId id = register();

        service.fetch(id, null, false);

        // The queue announces the start, then the engine's own report follows.
        assertEquals(42, events.progress.get(events.progress.size() - 1).percent());
        assertEquals("Receiving objects",
                events.progress.get(events.progress.size() - 1).phase());
    }

    @Test
    void cancellingTheOperationIsVisibleToTheEngine() throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            OperationQueue queue = new OperationQueue(
                    history, events, Clock.fixed(NOW, ZoneOffset.UTC), pool);
            RemoteService service = new RemoteService(
                    new VcsAccess(store, new DefaultVcsProviderRegistry(List.of(provider))),
                    queue, events);
            RepositoryId id = register();

            CountDownLatch inTheEngine = new CountDownLatch(1);
            CountDownLatch cancelRequested = new CountDownLatch(1);
            provider.beforeCheckingCancellation = () -> {
                inTheEngine.countDown();
                cancelRequested.await(5, TimeUnit.SECONDS);
            };

            Operation operation = service.fetch(id, null, false);
            assertTrue(inTheEngine.await(5, TimeUnit.SECONDS));
            queue.cancel(operation.id());
            cancelRequested.countDown();

            // A transfer runs for minutes; the engine polls this between chunks, and it is
            // the only place a download can stop without leaving a half-written pack.
            for (int i = 0; i < 100 && provider.observedCancellation == null; i++) {
                Thread.sleep(20);
            }
            assertEquals(Boolean.TRUE, provider.observedCancellation,
                    "the monitor must pass the cancellation signal through to the engine");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void everyCommandIsEchoedToTheConsole() {
        RemoteService service = serviceFor(provider);
        RepositoryId id = register();

        service.push(id, null, true, false);

        assertEquals(List.of("cmd:push origin --force-with-lease"), events.consoleLines);
    }

    @Test
    void unknownRepositoryIsReportedBeforeAnythingIsQueued() {
        RemoteService service = serviceFor(provider);

        assertThrows(NoSuchElementException.class,
                () -> service.fetch(RepositoryId.newId(), null, false));
        assertTrue(history.saved.isEmpty(), "a rejected request must not become an operation");
    }

    @Test
    void aProviderWithoutRemoteSupportIsReportedUpFront() {
        BareProvider bare = new BareProvider();
        provider.detects = false;
        RemoteService svnService = new RemoteService(
                new VcsAccess(store, new DefaultVcsProviderRegistry(List.of(provider, bare))),
                new OperationQueue(
                        history, events, Clock.fixed(NOW, ZoneOffset.UTC), Runnable::run),
                events);
        Repository repository = Repository.register(
                "demo", repoDir.toAbsolutePath().normalize(), null, VcsType.SVN, NOW);
        store.save(repository);

        assertThrows(UnsupportedOperationException.class,
                () -> svnService.push(repository.id(), null, false, false));
        assertTrue(history.saved.isEmpty());
    }

    // --- fakes -----------------------------------------------------------

    private static final class FakeRemoteProvider implements VcsProvider, RemoteSyncOperations {

        private boolean detects = true;
        private RuntimeException failWith;
        private OperationProgress reportProgress;

        /** Lets a test arrange for the operation to be cancelled mid-call. */
        private ThrowingRunnable beforeCheckingCancellation;
        private volatile Boolean observedCancellation;

        private FetchRequest lastFetch;
        private PullRequest lastPull;
        private PushRequest lastPush;

        @Override
        public VcsType type() {
            return VcsType.GIT;
        }

        @Override
        public Set<VcsCapability> capabilities() {
            return Set.of(VcsCapability.MERGE);
        }

        @Override
        public boolean detect(Path localPath) {
            return detects;
        }

        @Override
        public RepositoryHandle open(Path localPath) {
            return new RepositoryHandle(localPath, VcsType.GIT);
        }

        @Override
        public void fetch(RepositoryHandle repo, FetchRequest request, OperationMonitor monitor) {
            lastFetch = request;
            run(monitor);
        }

        @Override
        public void pull(RepositoryHandle repo, PullRequest request, OperationMonitor monitor) {
            lastPull = request;
            run(monitor);
        }

        @Override
        public void push(RepositoryHandle repo, PushRequest request, OperationMonitor monitor) {
            lastPush = request;
            run(monitor);
        }

        @Override
        public void update(RepositoryHandle repo, Long revision, OperationMonitor monitor) {
            throw new UnsupportedOperationException("not needed by these tests");
        }

        @Override
        public void cleanup(RepositoryHandle repo) {
            throw new UnsupportedOperationException("not needed by these tests");
        }

        /** Stands in for whatever the engine would do while transferring. */
        private void run(OperationMonitor monitor) {
            if (reportProgress != null) {
                monitor.onProgress(reportProgress);
            }
            if (beforeCheckingCancellation != null) {
                try {
                    beforeCheckingCancellation.run();
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
                observedCancellation = monitor.isCancelled();
            }
            if (failWith != null) {
                throw failWith;
            }
        }
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    /** Implements no remote port — stands in for a VCS that cannot sync. */
    private static final class BareProvider implements VcsProvider {

        @Override
        public VcsType type() {
            return VcsType.SVN;
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
            return new RepositoryHandle(localPath, VcsType.SVN);
        }
    }

    private static final class RecordingEvents implements OperationEvents {

        private final List<OperationProgress> progress = new ArrayList<>();
        private final List<String> consoleLines = new ArrayList<>();
        private final List<RepositoryId> refsChanged = new ArrayList<>();
        private final List<RepositoryId> workingTreeChanged = new ArrayList<>();

        @Override
        public void progress(OperationId id, OperationProgress value) {
            progress.add(value);
        }

        @Override
        public void completed(Operation operation) {
        }

        @Override
        public void consoleLine(
                RepositoryId repositoryId, OperationId id, String line, String level) {
            consoleLines.add(level + ":" + line);
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

    private static final class InMemoryHistory implements OperationHistoryStore {

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

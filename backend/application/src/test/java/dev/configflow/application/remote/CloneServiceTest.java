package dev.configflow.application.remote;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.configflow.application.operation.OperationQueue;
import dev.configflow.application.repository.RepositoryService;
import dev.configflow.application.vcs.DefaultVcsProviderRegistry;
import dev.configflow.domain.operation.Operation;
import dev.configflow.domain.operation.OperationEvents;
import dev.configflow.domain.operation.OperationHistoryStore;
import dev.configflow.domain.operation.OperationId;
import dev.configflow.domain.operation.OperationProgress;
import dev.configflow.domain.operation.OperationState;
import dev.configflow.domain.operation.OperationType;
import dev.configflow.domain.operation.WorkingTreeWatch;
import dev.configflow.domain.repository.Repository;
import dev.configflow.domain.repository.RepositoryId;
import dev.configflow.domain.repository.RepositoryStore;
import dev.configflow.domain.vcs.capability.VcsCapability;
import dev.configflow.domain.vcs.model.CloneRequest;
import dev.configflow.domain.vcs.model.RepositoryHandle;
import dev.configflow.domain.vcs.model.VcsType;
import dev.configflow.domain.vcs.port.OperationMonitor;
import dev.configflow.domain.vcs.port.RepositoryOperations;
import dev.configflow.domain.vcs.port.VcsProvider;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CloneServiceTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    private final InMemoryRepositoryStore store = new InMemoryRepositoryStore();
    private final FakeCloneProvider provider = new FakeCloneProvider();
    private final RecordingEvents events = new RecordingEvents();
    private final InMemoryHistory history = new InMemoryHistory();

    @TempDir
    Path root;

    private CloneService serviceFor(VcsProvider... providers) {
        return serviceOn(Runnable::run, providers);
    }

    private CloneService serviceOn(Executor executor, VcsProvider... providers) {
        DefaultVcsProviderRegistry registry = new DefaultVcsProviderRegistry(List.of(providers));
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        return new CloneService(
                registry,
                new RepositoryService(store, registry, clock, WorkingTreeWatch.noop()),
                new OperationQueue(history, events, clock, executor),
                events);
    }

    @Test
    void clone_runsTheEngineAndRegistersTheResult() {
        CloneService service = serviceFor(provider);
        Path target = root.resolve("repo");

        Operation operation = service.clone("https://host/o/repo.git", target, VcsType.GIT, null);

        assertEquals(OperationType.CLONE, operation.type());
        assertEquals("https://host/o/repo.git", provider.lastRequest.url());
        assertEquals(target, provider.lastRequest.localPath());
        assertEquals(1, store.findAll().size(), "the clone should join the workspace");
    }

    @Test
    void clone_announcesTheNewRepositorySoTheListCanCatchUp() {
        CloneService service = serviceFor(provider);

        service.clone("https://host/o/repo.git", root.resolve("repo"), VcsType.GIT, null);

        // The request that started this returned long ago; the list has no other signal.
        assertEquals(1, events.registered.size());
        assertEquals(store.findAll().get(0).id(), events.registered.get(0));
    }

    @Test
    void clone_hasNoRepositoryToQueueAgainst() {
        CloneService service = serviceFor(provider);

        Operation operation = service.clone(
                "https://host/o/repo.git", root.resolve("repo"), VcsType.GIT, null);

        assertEquals(null, operation.repositoryId(),
                "there is no repository until the clone finishes");
    }

    @Test
    void clone_defaultsToGitWhenNoVcsIsNamed() {
        CloneService service = serviceFor(provider);

        service.clone("https://host/o/repo.git", root.resolve("repo"), null, null);

        assertEquals(1, store.findAll().size());
    }

    @Test
    void clone_rejectsABlankUrlBeforeQueueingAnything() {
        CloneService service = serviceFor(provider);

        assertThrows(IllegalArgumentException.class,
                () -> service.clone("  ", root.resolve("repo"), VcsType.GIT, null));
        assertThrows(IllegalArgumentException.class,
                () -> service.clone(null, root.resolve("repo"), VcsType.GIT, null));
        assertTrue(history.saved.isEmpty(), "a rejected request must not become an operation");
    }

    @Test
    void clone_refusesADirectoryThatAlreadyHoldsSomething() throws IOException {
        CloneService service = serviceFor(provider);
        Path occupied = Files.createDirectory(root.resolve("occupied"));
        Files.writeString(occupied.resolve("existing.txt"), "mine");

        // A clone into a non-empty directory fails halfway, and the cleanup then cannot
        // tell what it created from what was already there.
        assertThrows(IllegalArgumentException.class,
                () -> service.clone("https://host/o/repo.git", occupied, VcsType.GIT, null));
        assertTrue(Files.exists(occupied.resolve("existing.txt")));
    }

    @Test
    void clone_acceptsAnEmptyDirectoryThatAlreadyExists() throws IOException {
        CloneService service = serviceFor(provider);
        Path empty = Files.createDirectory(root.resolve("empty"));

        service.clone("https://host/o/repo.git", empty, VcsType.GIT, null);

        assertEquals(1, store.findAll().size());
    }

    @Test
    void clone_requiresATarget() {
        CloneService service = serviceFor(provider);

        assertThrows(IllegalArgumentException.class,
                () -> service.clone("https://host/o/repo.git", null, VcsType.GIT, null));
    }

    @Test
    void clone_reportsAnEngineThatCannotCloneUpFront() {
        CloneService service = serviceFor(new BareProvider());

        assertThrows(UnsupportedOperationException.class,
                () -> service.clone("https://host/o/repo.git", root.resolve("repo"),
                        VcsType.SVN, null));
        assertTrue(history.saved.isEmpty());
    }

    @Test
    void clone_failureIsRecordedOnTheOperationAndRegistersNothing() {
        provider.failWith = new IllegalStateException("host unreachable");
        CloneService service = serviceFor(provider);

        Operation operation = service.clone(
                "https://host/o/repo.git", root.resolve("repo"), VcsType.GIT, null);

        assertEquals(OperationState.FAILED, history.saved.get(operation.id()).state());
        assertTrue(store.findAll().isEmpty(), "nothing was cloned, so nothing to register");
        assertTrue(events.registered.isEmpty());
    }

    @Test
    void clone_intoTheSameTargetRunsOneAtATime() throws Exception {
        ExecutorService pool = Executors.newCachedThreadPool();
        try {
            // Without this gate, a pool thread can run and finish the first queued clone
            // before the request thread submits the second/third — their pre-check would
            // then see a non-empty directory and throw synchronously instead of getting
            // queued as a FAILED operation, flaking the assertions below.
            CountDownLatch allSubmitted = new CountDownLatch(1);
            Executor gated = task -> pool.execute(() -> {
                try {
                    allSubmitted.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                task.run();
            });
            CloneService service = serviceOn(gated, provider);
            Path target = root.resolve("contested");
            events.completions = new CountDownLatch(3);

            // The emptiness check in clone() runs on the request thread, so all three get
            // past it before any of them starts transferring. Only the queue keeps them
            // apart after that.
            List<Operation> submitted = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                submitted.add(service.clone("https://host/o/repo.git", target, VcsType.GIT, null));
            }
            allSubmitted.countDown();

            assertTrue(events.completions.await(10, TimeUnit.SECONDS));
            assertEquals(1, provider.peakConcurrent.get(),
                    "two clones into one directory would write over each other");

            // The losers re-check the directory once they hold the chain, so they stop
            // with a plain "not empty" instead of tearing up what the winner wrote.
            List<OperationState> states = submitted.stream()
                    .map(operation -> history.saved.get(operation.id()).state())
                    .toList();
            assertEquals(1, states.stream().filter(s -> s == OperationState.SUCCEEDED).count());
            assertEquals(2, states.stream().filter(s -> s == OperationState.FAILED).count());
            assertEquals(1, store.findAll().size(), "one clone, one registration");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void clone_thatCannotBeRegisteredKeepsWhatItAlreadyDownloaded() {
        CloneService service = serviceFor(provider);
        Path target = root.resolve("taken");
        // The one failure register reliably produces: that path is already in the
        // workspace. Deleting here would destroy the working copy it points at.
        store.save(Repository.register("taken", target.toAbsolutePath().normalize(),
                null, VcsType.GIT, NOW));

        Operation operation = service.clone("https://host/o/repo.git", target, VcsType.GIT, null);

        assertEquals(OperationState.FAILED, history.saved.get(operation.id()).state());
        assertTrue(Files.exists(target.resolve("cloned.txt")),
                "the transfer succeeded, so its result must survive the bookkeeping failure");
        String message = history.saved.get(operation.id()).failure().message();
        assertTrue(message.contains(target.toString()), "the message must name the path: " + message);
    }

    // --- fakes -----------------------------------------------------------

    private static final class FakeCloneProvider implements VcsProvider, RepositoryOperations {

        private final AtomicInteger concurrent = new AtomicInteger();
        private final AtomicInteger peakConcurrent = new AtomicInteger();

        private volatile CloneRequest lastRequest;
        private volatile RuntimeException failWith;
        private volatile Runnable onClone;

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
            return true;
        }

        @Override
        public RepositoryHandle open(Path localPath) {
            return new RepositoryHandle(localPath, VcsType.GIT);
        }

        @Override
        public RepositoryHandle cloneRepository(CloneRequest request, OperationMonitor monitor) {
            if (failWith != null) {
                throw failWith;
            }
            peakConcurrent.accumulateAndGet(concurrent.incrementAndGet(), Math::max);
            try {
                lastRequest = request;
                monitor.onProgress(new OperationProgress(50, "Receiving objects", null));
                // A real clone leaves something behind; tests about cleanup need that.
                try {
                    Files.createDirectories(request.localPath());
                    Files.writeString(request.localPath().resolve("cloned.txt"), "content");
                    Thread.sleep(15);
                } catch (IOException | InterruptedException e) {
                    throw new IllegalStateException(e);
                }
                return new RepositoryHandle(request.localPath(), VcsType.GIT);
            } finally {
                concurrent.decrementAndGet();
                if (onClone != null) {
                    onClone.run();
                }
            }
        }

        @Override
        public RepositoryHandle init(Path path) {
            return new RepositoryHandle(path, VcsType.GIT);
        }
    }

    /** Cannot clone — stands in for a VCS without repository acquisition. */
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

        private final List<RepositoryId> registered =
                Collections.synchronizedList(new ArrayList<>());

        /** Set by tests that run off the calling thread and need to wait for the end. */
        private volatile CountDownLatch completions;

        @Override
        public void progress(OperationId id, OperationProgress progress) {
        }

        @Override
        public void completed(Operation operation) {
            if (completions != null) {
                completions.countDown();
            }
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
        }

        @Override
        public void repositoryRegistered(RepositoryId repositoryId) {
            registered.add(repositoryId);
        }
    }

    private static final class InMemoryHistory implements OperationHistoryStore {

        private final Map<OperationId, Operation> saved =
                Collections.synchronizedMap(new LinkedHashMap<>());

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

        private final Map<RepositoryId, Repository> byId =
                Collections.synchronizedMap(new LinkedHashMap<>());

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

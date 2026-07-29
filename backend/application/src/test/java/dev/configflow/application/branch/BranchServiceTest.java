package dev.configflow.application.branch;

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
import dev.configflow.domain.operation.OperationState;
import dev.configflow.domain.operation.OperationType;
import dev.configflow.domain.repository.Repository;
import dev.configflow.domain.repository.RepositoryId;
import dev.configflow.domain.repository.RepositoryStore;
import dev.configflow.domain.vcs.capability.VcsCapability;
import dev.configflow.domain.vcs.exception.MergeConflictException;
import dev.configflow.domain.vcs.model.MergeRequest;
import dev.configflow.domain.vcs.model.RepositoryHandle;
import dev.configflow.domain.vcs.model.VcsType;
import dev.configflow.domain.vcs.port.BranchOperations;
import dev.configflow.domain.vcs.port.VcsProvider;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BranchServiceTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    private final InMemoryRepositoryStore store = new InMemoryRepositoryStore();
    private final FakeBranchProvider provider = new FakeBranchProvider();
    private final NoHistory history = new NoHistory();

    @TempDir
    Path repoDir;

    private BranchService serviceFor(VcsProvider... providers) {
        VcsAccess access = new VcsAccess(store, new DefaultVcsProviderRegistry(List.of(providers)));
        OperationQueue queue = new OperationQueue(
                history, OperationEvents.noop(), Clock.fixed(NOW, ZoneOffset.UTC), Runnable::run);
        return new BranchService(access, queue, OperationEvents.noop());
    }

    private RepositoryId register() {
        Repository repository = Repository.register(
                "demo", repoDir.toAbsolutePath().normalize(), null, VcsType.GIT, NOW);
        store.save(repository);
        return repository.id();
    }

    @Test
    void checkout_runsTheProviderAndReportsSuccess() {
        BranchService service = serviceFor(provider);
        RepositoryId id = register();

        Operation operation = service.checkout(id, "feature/x");

        assertEquals(OperationType.CHECKOUT, operation.type());
        assertEquals(List.of("checkout:feature/x"), provider.calls);
    }

    @Test
    void checkout_trimsTheRefBeforeHandingItOver() {
        BranchService service = serviceFor(provider);
        RepositoryId id = register();

        service.checkout(id, "  feature/x  ");

        assertEquals(List.of("checkout:feature/x"), provider.calls);
    }

    @Test
    void checkout_rejectsABlankRefWithoutQueueingAnything() {
        BranchService service = serviceFor(provider);
        RepositoryId id = register();

        assertThrows(IllegalArgumentException.class, () -> service.checkout(id, "  "));
        assertThrows(IllegalArgumentException.class, () -> service.checkout(id, null));
        assertTrue(provider.calls.isEmpty());
        assertTrue(history.saved.isEmpty(), "a rejected request must not become an operation");
    }

    @Test
    void checkout_unknownRepositoryIsNotFound() {
        BranchService service = serviceFor(provider);

        assertThrows(NoSuchElementException.class,
                () -> service.checkout(RepositoryId.newId(), "main"));
    }

    @Test
    void checkout_reportsAProviderWithoutBranchSupportUpFront() {
        // Validation and capability checks both happen before the work is queued, so the
        // caller learns synchronously instead of watching an operation fail.
        BranchService service = serviceFor(new BareProvider());
        Repository repository = Repository.register(
                "demo", repoDir.toAbsolutePath().normalize(), null, VcsType.SVN, NOW);
        store.save(repository);

        assertThrows(UnsupportedOperationException.class,
                () -> service.checkout(repository.id(), "main"));
    }

    @Test
    void checkout_failureIsRecordedOnTheOperationRatherThanThrown() {
        provider.failWith = new IllegalStateException("index.lock exists");
        BranchService service = serviceFor(provider);
        RepositoryId id = register();

        Operation operation = service.checkout(id, "feature/x");

        // Accepted work reports its outcome through the operation, not the submit call.
        assertEquals(OperationState.FAILED, history.saved.get(operation.id()).state());
        assertEquals("index.lock exists",
                history.saved.get(operation.id()).failure().message());
    }

    @Test
    void createBranch_passesTheStartPointAndCheckoutFlag() {
        BranchService service = serviceFor(provider);
        RepositoryId id = register();

        service.createBranch(id, "feature/x", "main", true);

        assertEquals(List.of("create:feature/x:main:true"), provider.calls);
    }

    @Test
    void createBranch_treatsABlankStartPointAsAbsent() {
        BranchService service = serviceFor(provider);
        RepositoryId id = register();

        service.createBranch(id, "feature/x", "   ", false);

        assertEquals(List.of("create:feature/x:null:false"), provider.calls);
    }

    @Test
    void createBranch_rejectsABlankName() {
        BranchService service = serviceFor(provider);
        RepositoryId id = register();

        assertThrows(IllegalArgumentException.class,
                () -> service.createBranch(id, " ", null, false));
        assertTrue(provider.calls.isEmpty());
    }

    @Test
    void deleteBranch_passesTheRemoteAndForceFlags() {
        BranchService service = serviceFor(provider);
        RepositoryId id = register();

        service.deleteBranch(id, "feature/x", true, true);

        assertEquals(List.of("delete:feature/x:true:true"), provider.calls);
        assertEquals(OperationType.BRANCH_DELETE,
                history.saved.values().iterator().next().type());
    }

    @Test
    void merge_passesTheSourceAndFlags() {
        BranchService service = serviceFor(provider);
        RepositoryId id = register();

        service.merge(id, "feature/x", true, false);

        assertEquals(List.of("merge:feature/x:true:false"), provider.calls);
        assertEquals(OperationType.MERGE,
                history.saved.values().iterator().next().type());
    }

    @Test
    void merge_trimsTheSourceBeforeHandingItOver() {
        BranchService service = serviceFor(provider);
        RepositoryId id = register();

        service.merge(id, "  feature/x  ", false, true);

        assertEquals(List.of("merge:feature/x:false:true"), provider.calls);
    }

    @Test
    void merge_rejectsABlankSourceWithoutQueueingAnything() {
        BranchService service = serviceFor(provider);
        RepositoryId id = register();

        assertThrows(IllegalArgumentException.class, () -> service.merge(id, "  ", false, false));
        assertThrows(IllegalArgumentException.class, () -> service.merge(id, null, false, false));
        assertTrue(provider.calls.isEmpty());
        assertTrue(history.saved.isEmpty(), "a rejected request must not become an operation");
    }

    @Test
    void merge_conflictIsRecordedOnTheOperationRatherThanThrown() {
        // A content conflict is not an error the caller can prevent — the working tree is
        // left conflicted and the failure rides the operation, not the submit call.
        provider.failWith = new MergeConflictException(List.of(Path.of("app.txt")));
        BranchService service = serviceFor(provider);
        RepositoryId id = register();

        Operation operation = service.merge(id, "feature/x", false, false);

        assertEquals(OperationState.FAILED, history.saved.get(operation.id()).state());
    }

    // --- fakes -----------------------------------------------------------

    private static final class FakeBranchProvider implements VcsProvider, BranchOperations {

        private final List<String> calls = new ArrayList<>();
        private RuntimeException failWith;

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
        public void checkout(RepositoryHandle repo, String ref) {
            if (failWith != null) {
                throw failWith;
            }
            calls.add("checkout:" + ref);
        }

        @Override
        public void createBranch(
                RepositoryHandle repo, String name, String startPoint, boolean checkout) {
            calls.add("create:" + name + ":" + startPoint + ":" + checkout);
        }

        @Override
        public void deleteBranch(
                RepositoryHandle repo, String name, boolean remote, boolean force) {
            calls.add("delete:" + name + ":" + remote + ":" + force);
        }

        @Override
        public void merge(RepositoryHandle repo, MergeRequest request) {
            if (failWith != null) {
                throw failWith;
            }
            calls.add("merge:" + request.source()
                    + ":" + request.fastForwardOnly() + ":" + request.squash());
        }
    }

    /** Implements no operation port — stands in for a VCS that cannot branch. */
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

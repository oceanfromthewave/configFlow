package dev.configflow.application.svn;

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
import dev.configflow.domain.vcs.model.RemoteEntry;
import dev.configflow.domain.vcs.model.RepositoryHandle;
import dev.configflow.domain.vcs.model.RevisionId;
import dev.configflow.domain.vcs.model.VcsType;
import dev.configflow.domain.vcs.port.LockOperations;
import dev.configflow.domain.vcs.port.OperationMonitor;
import dev.configflow.domain.vcs.port.RemoteBrowseOperations;
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

class SvnServiceTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    private final InMemoryRepositoryStore store = new InMemoryRepositoryStore();
    private final FakeSvnProvider provider = new FakeSvnProvider();
    private final RecordingEvents events = new RecordingEvents();
    private final NoHistory history = new NoHistory();

    @TempDir
    Path repoDir;

    private SvnService serviceFor(VcsProvider... providers) {
        VcsAccess access = new VcsAccess(store, new DefaultVcsProviderRegistry(List.of(providers)));
        OperationQueue queue = new OperationQueue(
                history, events, Clock.fixed(NOW, ZoneOffset.UTC), Runnable::run);
        return new SvnService(access, queue, events);
    }

    private RepositoryId register() {
        Repository repository = Repository.register(
                "demo", repoDir.toAbsolutePath().normalize(), null, VcsType.SVN, NOW);
        store.save(repository);
        return repository.id();
    }

    // --- lock / unlock -----------------------------------------------------

    @Test
    void lock_forwardsPathsAndComment() {
        SvnService service = serviceFor(provider);
        RepositoryId id = register();

        service.lock(id, List.of(Path.of("a.txt")), "reserving");

        assertEquals(List.of(Path.of("a.txt")), provider.lastLockPaths);
        assertEquals("reserving", provider.lastLockComment);
    }

    @Test
    void lock_rejectsAnEmptyPathList() {
        SvnService service = serviceFor(provider);
        RepositoryId id = register();

        assertThrows(IllegalArgumentException.class, () -> service.lock(id, List.of(), null));
    }

    @Test
    void lock_announcesTheWorkingTreeChanged() {
        SvnService service = serviceFor(provider);
        RepositoryId id = register();

        service.lock(id, List.of(Path.of("a.txt")), null);

        assertEquals(List.of(id), events.workingTreeChanged);
    }

    @Test
    void unlock_forwardsBreakLock() {
        SvnService service = serviceFor(provider);
        RepositoryId id = register();

        service.unlock(id, List.of(Path.of("a.txt")), true);

        assertEquals(List.of(Path.of("a.txt")), provider.lastUnlockPaths);
        assertTrue(provider.lastBreakLock);
    }

    @Test
    void unlock_rejectsAnEmptyPathList() {
        SvnService service = serviceFor(provider);
        RepositoryId id = register();

        assertThrows(IllegalArgumentException.class, () -> service.unlock(id, List.of(), false));
    }

    // --- browse --------------------------------------------------------

    @Test
    void browse_forwardsTheUrlAndRevision() {
        SvnService service = serviceFor(provider);
        RepositoryId id = register();
        provider.browseResult = List.of(new RemoteEntry("trunk", true, 0, new RevisionId("r5")));

        List<RemoteEntry> entries = service.browse(id, "file:///repo/trunk", "5");

        assertEquals(provider.browseResult, entries);
        assertEquals("file:///repo/trunk", provider.lastBrowseUrl);
        assertEquals(new RevisionId("5"), provider.lastBrowseRevision);
    }

    @Test
    void browse_omittedRevisionMeansHead() {
        SvnService service = serviceFor(provider);
        RepositoryId id = register();

        service.browse(id, "file:///repo/trunk", null);

        assertEquals(null, provider.lastBrowseRevision);
    }

    @Test
    void browse_rejectsABlankUrl() {
        SvnService service = serviceFor(provider);
        RepositoryId id = register();

        assertThrows(IllegalArgumentException.class, () -> service.browse(id, "  ", null));
    }

    @Test
    void aProviderWithoutLockSupportIsReportedUpFront() {
        BareProvider bare = new BareProvider();
        SvnService service = new SvnService(
                new VcsAccess(store, new DefaultVcsProviderRegistry(List.of(bare))),
                new OperationQueue(history, events, Clock.fixed(NOW, ZoneOffset.UTC), Runnable::run),
                events);
        Repository repository = Repository.register(
                "demo", repoDir.toAbsolutePath().normalize(), null, VcsType.GIT, NOW);
        store.save(repository);

        assertThrows(UnsupportedOperationException.class,
                () -> service.lock(repository.id(), List.of(Path.of("a.txt")), null));
    }

    // --- fakes -----------------------------------------------------------

    private static final class FakeSvnProvider implements VcsProvider, LockOperations, RemoteBrowseOperations {

        private List<Path> lastLockPaths;
        private String lastLockComment;
        private List<Path> lastUnlockPaths;
        private boolean lastBreakLock;
        private String lastBrowseUrl;
        private RevisionId lastBrowseRevision;
        private List<RemoteEntry> browseResult = List.of();

        @Override
        public VcsType type() {
            return VcsType.SVN;
        }

        @Override
        public Set<VcsCapability> capabilities() {
            return Set.of(VcsCapability.LOCK, VcsCapability.REMOTE_BROWSE);
        }

        @Override
        public boolean detect(Path localPath) {
            return true;
        }

        @Override
        public RepositoryHandle open(Path localPath) {
            return new RepositoryHandle(localPath, VcsType.SVN);
        }

        @Override
        public void lock(RepositoryHandle repo, List<Path> paths, String comment, OperationMonitor monitor) {
            lastLockPaths = paths;
            lastLockComment = comment;
        }

        @Override
        public void unlock(RepositoryHandle repo, List<Path> paths, boolean breakLock, OperationMonitor monitor) {
            lastUnlockPaths = paths;
            lastBreakLock = breakLock;
        }

        @Override
        public List<RemoteEntry> browse(String url, RevisionId revision) {
            lastBrowseUrl = url;
            lastBrowseRevision = revision;
            return browseResult;
        }
    }

    /** Implements no SVN-only port — stands in for a VCS that cannot lock or browse. */
    private static final class BareProvider implements VcsProvider {

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
        public void consoleLine(RepositoryId repositoryId, OperationId id, String line, String level) {
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

        @Override
        public void save(Operation operation) {
        }

        @Override
        public Optional<Operation> findById(OperationId id) {
            return Optional.empty();
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

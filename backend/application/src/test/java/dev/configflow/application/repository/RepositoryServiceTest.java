package dev.configflow.application.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.configflow.application.vcs.DefaultVcsProviderRegistry;
import dev.configflow.domain.repository.Repository;
import dev.configflow.domain.repository.RepositoryId;
import dev.configflow.domain.repository.RepositoryStore;
import dev.configflow.domain.vcs.capability.VcsCapability;
import dev.configflow.domain.vcs.model.Author;
import dev.configflow.domain.vcs.model.ChangeType;
import dev.configflow.domain.vcs.model.CommitRequest;
import dev.configflow.domain.vcs.model.FileChange;
import dev.configflow.domain.vcs.model.FileDiff;
import dev.configflow.domain.vcs.model.HistoryQuery;
import dev.configflow.domain.vcs.model.IgnorePattern;
import dev.configflow.domain.vcs.model.Page;
import dev.configflow.domain.vcs.model.RepositoryHandle;
import dev.configflow.domain.vcs.model.Revision;
import dev.configflow.domain.vcs.model.RevisionId;
import dev.configflow.domain.vcs.model.VcsType;
import dev.configflow.domain.vcs.model.WorkingTreeStatus;
import dev.configflow.domain.vcs.port.CommitOperations;
import dev.configflow.domain.vcs.port.DiffOperations;
import dev.configflow.domain.vcs.port.VcsProvider;
import dev.configflow.domain.vcs.port.WorkingTreeOperations;
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

class RepositoryServiceTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    private final InMemoryRepositoryStore store = new InMemoryRepositoryStore();
    private final FakeGitProvider provider = new FakeGitProvider();
    private final RepositoryService service = new RepositoryService(
            store, new DefaultVcsProviderRegistry(List.of(provider)), Clock.fixed(NOW, ZoneOffset.UTC));

    @TempDir
    Path repoDir;

    @Test
    void register_detectsVcsAndPersists() {
        Repository repo = service.register(repoDir);

        assertEquals(VcsType.GIT, repo.vcsType());
        assertEquals(repoDir.getFileName().toString(), repo.name());
        assertEquals(repoDir.toAbsolutePath().normalize(), repo.localPath());
        assertEquals(NOW, repo.createdAt());
        assertTrue(store.findById(repo.id()).isPresent());
    }

    @Test
    void register_rejectsUnsupportedPath() {
        provider.detects = false;

        assertThrows(IllegalArgumentException.class, () -> service.register(repoDir));
    }

    @Test
    void register_rejectsDuplicate() {
        service.register(repoDir);

        assertThrows(IllegalArgumentException.class, () -> service.register(repoDir));
    }

    @Test
    void status_returnsWhatTheProviderReports() {
        RepositoryId id = service.register(repoDir).id();
        provider.status = new WorkingTreeStatus(
                List.of(),
                List.of(FileChange.of(Path.of("new.txt"), ChangeType.UNTRACKED)),
                List.of());

        WorkingTreeStatus status = service.status(id);

        assertEquals(1, status.unstaged().size());
        assertEquals(Path.of("new.txt"), status.unstaged().get(0).path());
    }

    @Test
    void status_unknownIdThrowsNotFound() {
        assertThrows(NoSuchElementException.class, () -> service.status(RepositoryId.newId()));
    }

    @Test
    void open_setsLastOpenedTime() {
        Repository registered = service.register(repoDir);
        assertNull(registered.lastOpenedAt());

        Repository opened = service.open(registered.id());

        assertEquals(NOW, opened.lastOpenedAt());
    }

    @Test
    void stage_forwardsPathsToTheProvider() {
        RepositoryId id = service.register(repoDir).id();

        service.stage(id, List.of(Path.of("a.txt"), Path.of("src/b.txt")));

        assertEquals(List.of(Path.of("a.txt"), Path.of("src/b.txt")), provider.staged);
    }

    @Test
    void unstage_forwardsPathsToTheProvider() {
        RepositoryId id = service.register(repoDir).id();

        service.unstage(id, List.of(Path.of("a.txt")));

        assertEquals(List.of(Path.of("a.txt")), provider.unstaged);
    }

    @Test
    void stage_rejectsAnEmptySelection() {
        RepositoryId id = service.register(repoDir).id();

        assertThrows(IllegalArgumentException.class, () -> service.stage(id, List.of()));
        assertThrows(IllegalArgumentException.class, () -> service.stage(id, null));
    }

    @Test
    void stage_rejectsPathsEscapingTheWorkingCopy() {
        RepositoryId id = service.register(repoDir).id();

        assertThrows(IllegalArgumentException.class,
                () -> service.stage(id, List.of(Path.of("../outside.txt"))));
        assertThrows(IllegalArgumentException.class,
                () -> service.stage(id, List.of(Path.of("src/../../outside.txt"))));
        assertThrows(IllegalArgumentException.class,
                () -> service.stage(id, List.of(repoDir.resolve("absolute.txt"))));
        assertTrue(provider.staged.isEmpty(), "nothing may reach the provider");
    }

    @Test
    void commit_returnsTheCreatedRevisionId() {
        RepositoryId id = service.register(repoDir).id();

        RevisionId created = service.commit(id, CommitRequest.of("feat: add a thing"));

        assertEquals(FakeGitProvider.CREATED, created);
        assertEquals("feat: add a thing", provider.lastCommit.message());
    }

    @Test
    void commit_rejectsABlankMessage() {
        RepositoryId id = service.register(repoDir).id();

        assertThrows(IllegalArgumentException.class,
                () -> service.commit(id, CommitRequest.of("   ")));
        assertNull(provider.lastCommit);
    }

    @Test
    void commit_rejectsAmendWhenTheProviderCannotAmend() {
        RepositoryId id = service.register(repoDir).id();
        provider.capabilities = Set.of(VcsCapability.STAGING);
        CommitRequest amend = new CommitRequest("reword", true, List.of(), false);

        assertThrows(UnsupportedOperationException.class, () -> service.commit(id, amend));
        assertNull(provider.lastCommit);
    }

    @Test
    void history_passesTheQueryThroughAndReturnsThePage() {
        RepositoryId id = service.register(repoDir).id();
        HistoryQuery query = new HistoryQuery(null, 10, "main", "alice", "fix", null, null, null);

        Page<Revision> page = service.history(id, query);

        assertSame(query, provider.lastQuery);
        assertEquals(1, page.items().size());
        assertEquals("next-cursor", page.nextCursor());
    }

    @Test
    void history_rejectsAPageLargerThanTheCap() {
        RepositoryId id = service.register(repoDir).id();
        HistoryQuery tooBig = HistoryQuery.firstPage(RepositoryService.MAX_HISTORY_LIMIT + 1);

        assertThrows(IllegalArgumentException.class, () -> service.history(id, tooBig));
        assertNull(provider.lastQuery, "the request must not reach the provider");
    }

    @Test
    void history_allowsExactlyTheCap() {
        RepositoryId id = service.register(repoDir).id();

        service.history(id, HistoryQuery.firstPage(RepositoryService.MAX_HISTORY_LIMIT));

        assertEquals(RepositoryService.MAX_HISTORY_LIMIT, provider.lastQuery.limit());
    }

    @Test
    void show_delegatesToTheProvider() {
        RepositoryId id = service.register(repoDir).id();
        RevisionId wanted = new RevisionId("HEAD");

        Revision revision = service.show(id, wanted);

        assertEquals(wanted, provider.lastShown);
        assertEquals("only commit", revision.message());
    }

    @Test
    void diffWorking_forwardsThePathAndTheStagedFlag() {
        RepositoryId id = service.register(repoDir).id();

        FileDiff diff = service.diffWorking(id, Path.of("src/app.ts"), true);

        assertEquals(Path.of("src/app.ts"), provider.lastDiffPath);
        assertTrue(provider.lastDiffStaged);
        assertEquals(ChangeType.MODIFIED, diff.type());
    }

    @Test
    void diffWorking_rejectsPathsEscapingTheWorkingCopy() {
        RepositoryId id = service.register(repoDir).id();

        assertThrows(IllegalArgumentException.class,
                () -> service.diffWorking(id, Path.of("../outside.txt"), false));
        assertThrows(IllegalArgumentException.class,
                () -> service.diffWorking(id, repoDir.resolve("absolute.txt"), false));
        assertThrows(IllegalArgumentException.class,
                () -> service.diffWorking(id, null, false));
        assertNull(provider.lastDiffPath, "nothing may reach the provider");
    }

    @Test
    void commit_rejectsProvidersWithoutCommitOperations() {
        BareProvider bare = new BareProvider();
        provider.detects = false;
        RepositoryService svnService = new RepositoryService(
                store,
                new DefaultVcsProviderRegistry(List.of(provider, bare)),
                Clock.fixed(NOW, ZoneOffset.UTC));
        RepositoryId id = svnService.register(repoDir).id();

        assertThrows(UnsupportedOperationException.class,
                () -> svnService.commit(id, CommitRequest.of("nope")));
    }

    // --- fakes: hand-written test doubles of the domain ports ------------

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
            return byId.values().stream()
                    .filter(r -> r.localPath().equals(localPath))
                    .findFirst();
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

    private static final class FakeGitProvider
            implements VcsProvider, WorkingTreeOperations, CommitOperations, DiffOperations {

        static final RevisionId CREATED = new RevisionId("0123456789abcdef");
        static final Revision REVISION = new Revision(
                CREATED,
                List.of(),
                new Author("Test", "test@configflow.dev"),
                NOW,
                "only commit",
                List.of());

        boolean detects = true;
        WorkingTreeStatus status = WorkingTreeStatus.clean();
        Set<VcsCapability> capabilities = Set.of(VcsCapability.STAGING, VcsCapability.AMEND);
        final List<Path> staged = new ArrayList<>();
        final List<Path> unstaged = new ArrayList<>();
        CommitRequest lastCommit;
        HistoryQuery lastQuery;
        RevisionId lastShown;
        Path lastDiffPath;
        boolean lastDiffStaged;

        @Override
        public VcsType type() {
            return VcsType.GIT;
        }

        @Override
        public Set<VcsCapability> capabilities() {
            return capabilities;
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
        public WorkingTreeStatus status(RepositoryHandle repo) {
            return status;
        }

        @Override
        public void stage(RepositoryHandle repo, List<Path> paths) {
            staged.addAll(paths);
        }

        @Override
        public void unstage(RepositoryHandle repo, List<Path> paths) {
            unstaged.addAll(paths);
        }

        @Override
        public void discard(RepositoryHandle repo, List<Path> paths) {
        }

        @Override
        public void ignore(RepositoryHandle repo, IgnorePattern pattern) {
        }

        @Override
        public RevisionId commit(RepositoryHandle repo, CommitRequest request) {
            lastCommit = request;
            return CREATED;
        }

        @Override
        public Page<Revision> history(RepositoryHandle repo, HistoryQuery query) {
            lastQuery = query;
            return new Page<>(List.of(REVISION), "next-cursor");
        }

        @Override
        public Revision show(RepositoryHandle repo, RevisionId id) {
            lastShown = id;
            return REVISION;
        }

        @Override
        public FileDiff diffWorking(RepositoryHandle repo, Path path, boolean staged) {
            lastDiffPath = path;
            lastDiffStaged = staged;
            return new FileDiff(path, null, ChangeType.MODIFIED, false, List.of());
        }

        @Override
        public FileDiff diffRevisions(
                RepositoryHandle repo, RevisionId from, RevisionId to, Path path) {
            throw new UnsupportedOperationException("not needed by these tests");
        }

        @Override
        public String contentAt(RepositoryHandle repo, RevisionId revision, Path path) {
            throw new UnsupportedOperationException("not needed by these tests");
        }
    }

    /** A provider that implements no operation port — stands in for a limited VCS. */
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
}

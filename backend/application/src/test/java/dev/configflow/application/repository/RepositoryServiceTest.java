package dev.configflow.application.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.configflow.application.vcs.DefaultVcsProviderRegistry;
import dev.configflow.domain.repository.Repository;
import dev.configflow.domain.repository.RepositoryId;
import dev.configflow.domain.repository.RepositoryStore;
import dev.configflow.domain.vcs.capability.VcsCapability;
import dev.configflow.domain.vcs.model.ChangeType;
import dev.configflow.domain.vcs.model.FileChange;
import dev.configflow.domain.vcs.model.IgnorePattern;
import dev.configflow.domain.vcs.model.RepositoryHandle;
import dev.configflow.domain.vcs.model.VcsType;
import dev.configflow.domain.vcs.model.WorkingTreeStatus;
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

    private static final class FakeGitProvider implements VcsProvider, WorkingTreeOperations {
        boolean detects = true;
        WorkingTreeStatus status = WorkingTreeStatus.clean();

        @Override
        public VcsType type() {
            return VcsType.GIT;
        }

        @Override
        public Set<VcsCapability> capabilities() {
            return Set.of(VcsCapability.STAGING);
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
        }

        @Override
        public void unstage(RepositoryHandle repo, List<Path> paths) {
        }

        @Override
        public void discard(RepositoryHandle repo, List<Path> paths) {
        }

        @Override
        public void ignore(RepositoryHandle repo, IgnorePattern pattern) {
        }
    }
}

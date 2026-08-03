package dev.configflow.infrastructure.svn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.configflow.domain.vcs.model.ChangeType;
import dev.configflow.domain.vcs.model.CloneRequest;
import dev.configflow.domain.vcs.model.FileChange;
import dev.configflow.domain.vcs.model.RepositoryHandle;
import dev.configflow.domain.vcs.model.VcsType;
import dev.configflow.domain.vcs.model.WorkingTreeStatus;
import dev.configflow.domain.vcs.port.OperationMonitor;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.tmatesoft.svn.core.SVNURL;
import org.tmatesoft.svn.core.internal.io.fs.FSRepositoryFactory;
import org.tmatesoft.svn.core.io.SVNRepositoryFactory;
import org.tmatesoft.svn.core.wc.SVNClientManager;

class SvnVcsProviderTest {

    static {
        FSRepositoryFactory.setup();
    }

    private final SvnVcsProvider provider = new SvnVcsProvider();

    @TempDir
    Path serverDir;

    @TempDir
    Path checkoutDir;

    private SVNURL repositoryUrl;

    @BeforeEach
    void createServerRepository() throws Exception {
        // A "remote" for these tests: SVNKit can address a plain FS repository via
        // file:// just like it would a real svnserve/Apache host.
        Path repoRoot = serverDir.resolve("repo");
        repositoryUrl = SVNRepositoryFactory.createLocalRepository(repoRoot.toFile(), true, false);

        Path seed = Files.createTempDirectory("svn-seed");
        Files.writeString(seed.resolve("base.txt"), "base\n");
        SVNClientManager clients = SVNClientManager.newInstance();
        try {
            clients.getCommitClient().doImport(seed.toFile(), repositoryUrl, "initial import", true);
        } finally {
            clients.dispose();
        }
    }

    @Test
    void cloneRepositoryChecksOutTheRemoteIntoTheGivenDirectory() {
        Path target = checkoutDir.resolve("wc");

        RepositoryHandle handle = provider.cloneRepository(
                new CloneRequest(repositoryUrl.toString(), target, null), OperationMonitor.noop());

        assertEquals(target, handle.localPath());
        assertEquals(VcsType.SVN, handle.vcsType());
        assertTrue(Files.exists(target.resolve("base.txt")));
        assertTrue(provider.detect(target));
    }

    @Test
    void cloneRepositoryRejectsAMalformedUrl() {
        Path target = checkoutDir.resolve("wc");

        assertThrows(IllegalArgumentException.class,
                () -> provider.cloneRepository(new CloneRequest("not a url", target, null), OperationMonitor.noop()));
    }

    @Test
    void initIsNotSupported() {
        assertThrows(UnsupportedOperationException.class, () -> provider.init(checkoutDir));
    }

    @Test
    void statusIsCleanRightAfterCheckout() {
        RepositoryHandle handle = checkout();

        WorkingTreeStatus status = provider.status(handle);

        assertTrue(status.isClean());
        assertTrue(status.staged().isEmpty());
        assertFalse(status.rebasing());
    }

    @Test
    void statusReportsAModifiedFileAsUnstaged() throws IOException {
        RepositoryHandle handle = checkout();
        Files.writeString(handle.localPath().resolve("base.txt"), "changed\n");

        WorkingTreeStatus status = provider.status(handle);

        assertEquals(List.of(FileChange.of(Path.of("base.txt"), ChangeType.MODIFIED)), status.unstaged());
        assertTrue(status.staged().isEmpty());
    }

    @Test
    void statusReportsANewFileAsUntracked() throws IOException {
        RepositoryHandle handle = checkout();
        Files.writeString(handle.localPath().resolve("new.txt"), "new\n");

        WorkingTreeStatus status = provider.status(handle);

        assertEquals(List.of(FileChange.of(Path.of("new.txt"), ChangeType.UNTRACKED)), status.unstaged());
    }

    @Test
    void statusReportsASvnDeletedFileAsDeleted() throws Exception {
        RepositoryHandle handle = checkout();
        SVNClientManager clients = SVNClientManager.newInstance();
        try {
            clients.getWCClient().doDelete(handle.localPath().resolve("base.txt").toFile(), true, false);
        } finally {
            clients.dispose();
        }

        WorkingTreeStatus status = provider.status(handle);

        assertEquals(List.of(FileChange.of(Path.of("base.txt"), ChangeType.DELETED)), status.unstaged());
    }

    @Test
    void stageIsNotSupported() {
        RepositoryHandle handle = checkout();
        assertThrows(UnsupportedOperationException.class, () -> provider.stage(handle, List.of()));
    }

    @Test
    void unstageIsNotSupported() {
        RepositoryHandle handle = checkout();
        assertThrows(UnsupportedOperationException.class, () -> provider.unstage(handle, List.of()));
    }

    @Test
    void discardIsNotSupportedYet() {
        RepositoryHandle handle = checkout();
        assertThrows(UnsupportedOperationException.class, () -> provider.discard(handle, List.of()));
    }

    @Test
    void ignoreIsNotSupportedYet() {
        RepositoryHandle handle = checkout();
        assertThrows(UnsupportedOperationException.class, () -> provider.ignore(handle, null));
    }

    private RepositoryHandle checkout() {
        Path target = checkoutDir.resolve("wc");
        return provider.cloneRepository(new CloneRequest(repositoryUrl.toString(), target, null), OperationMonitor.noop());
    }
}

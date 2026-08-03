package dev.configflow.infrastructure.svn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.configflow.domain.vcs.exception.VcsPreconditionException;
import dev.configflow.domain.vcs.model.ChangeType;
import dev.configflow.domain.vcs.model.CloneRequest;
import dev.configflow.domain.vcs.model.CommitRequest;
import dev.configflow.domain.vcs.model.FileChange;
import dev.configflow.domain.vcs.model.HistoryQuery;
import dev.configflow.domain.vcs.model.IgnorePattern;
import dev.configflow.domain.vcs.model.Page;
import dev.configflow.domain.vcs.model.RepositoryHandle;
import dev.configflow.domain.vcs.model.Revision;
import dev.configflow.domain.vcs.model.RevisionId;
import dev.configflow.domain.vcs.model.VcsType;
import dev.configflow.domain.vcs.model.WorkingTreeStatus;
import dev.configflow.domain.vcs.port.OperationMonitor;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.NoSuchElementException;
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

        Path seed = Files.createTempDirectory(serverDir, "svn-seed");
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
    void discardOnAnEmptyListIsANoop() {
        RepositoryHandle handle = checkout();
        provider.discard(handle, List.of());
    }

    @Test
    void updateBringsInACommitMadeElsewhereOnTheSameServer() throws Exception {
        RepositoryHandle handle = checkout();
        commitDirectlyToServer("base.txt", "changed on the server\n");

        provider.update(handle, null, OperationMonitor.noop());

        assertEquals("changed on the server\n", Files.readString(handle.localPath().resolve("base.txt")));
    }

    @Test
    void updateToAnExplicitRevisionStopsThere() throws Exception {
        RepositoryHandle handle = checkout();
        commitDirectlyToServer("base.txt", "r2\n");
        commitDirectlyToServer("base.txt", "r3\n");

        provider.update(handle, 2L, OperationMonitor.noop());

        assertEquals("r2\n", Files.readString(handle.localPath().resolve("base.txt")));
    }

    @Test
    void updateRejectsANonPositiveRevision() {
        RepositoryHandle handle = checkout();

        assertThrows(IllegalArgumentException.class,
                () -> provider.update(handle, 0L, OperationMonitor.noop()));
    }

    @Test
    void cleanupRunsWithoutErrorOnAHealthyWorkingCopy() {
        RepositoryHandle handle = checkout();

        provider.cleanup(handle);
    }

    @Test
    void fetchPullAndPushAreNotSupported() {
        RepositoryHandle handle = checkout();

        assertThrows(UnsupportedOperationException.class,
                () -> provider.fetch(handle, null, OperationMonitor.noop()));
        assertThrows(UnsupportedOperationException.class,
                () -> provider.pull(handle, null, OperationMonitor.noop()));
        assertThrows(UnsupportedOperationException.class,
                () -> provider.push(handle, null, OperationMonitor.noop()));
    }

    @Test
    void discardRevertsALocalModification() throws IOException {
        RepositoryHandle handle = checkout();
        Files.writeString(handle.localPath().resolve("base.txt"), "local edit\n");

        provider.discard(handle, List.of(Path.of("base.txt")));

        assertEquals("base\n", Files.readString(handle.localPath().resolve("base.txt")));
        assertTrue(provider.status(handle).isClean());
    }

    @Test
    void ignoreAddsAGlobToTheDirectorysSvnIgnoreProperty() throws Exception {
        RepositoryHandle handle = checkout();

        provider.ignore(handle, new IgnorePattern("*.log"));
        Files.writeString(handle.localPath().resolve("debug.log"), "noise\n");

        WorkingTreeStatus status = provider.status(handle);
        assertTrue(status.unstaged().stream().noneMatch(c -> c.path().equals(Path.of("debug.log"))));
    }

    @Test
    void commitSendsModifiedPathsToTheServerAndReturnsTheNewRevision() throws IOException {
        RepositoryHandle handle = checkout();
        Files.writeString(handle.localPath().resolve("base.txt"), "committed change\n");

        RevisionId id = provider.commit(handle, CommitRequest.of("update base.txt"));

        assertEquals("r2", id.value());
        assertTrue(provider.status(handle).isClean());
    }

    @Test
    void commitWithNoChangesIsAPrecondition() {
        RepositoryHandle handle = checkout();

        assertThrows(VcsPreconditionException.class,
                () -> provider.commit(handle, CommitRequest.of("nothing to say")));
    }

    @Test
    void commitRejectsAmend() {
        RepositoryHandle handle = checkout();

        assertThrows(UnsupportedOperationException.class,
                () -> provider.commit(handle, new CommitRequest("amend", true, List.of(), false)));
    }

    @Test
    void historyReturnsRevisionsNewestFirst() throws Exception {
        RepositoryHandle handle = checkout();
        commitDirectlyToServer("base.txt", "r2\n");
        commitDirectlyToServer("base.txt", "r3\n");

        Page<Revision> page = provider.history(handle, HistoryQuery.firstPage(10));

        assertEquals(List.of("r3", "r2", "r1"),
                page.items().stream().map(r -> r.id().value()).toList());
        assertNull(page.nextCursor());
    }

    @Test
    void historyPagesUsingTheRevisionCursor() throws Exception {
        RepositoryHandle handle = checkout();
        commitDirectlyToServer("base.txt", "r2\n");
        commitDirectlyToServer("base.txt", "r3\n");

        Page<Revision> firstPage = provider.history(handle, HistoryQuery.firstPage(2));
        assertEquals(List.of("r3", "r2"),
                firstPage.items().stream().map(r -> r.id().value()).toList());
        assertEquals("r1", firstPage.nextCursor());

        Page<Revision> secondPage = provider.history(handle,
                new HistoryQuery(firstPage.nextCursor(), 2, null, null, null, null, null, null));
        assertEquals(List.of("r1"), secondPage.items().stream().map(r -> r.id().value()).toList());
        assertNull(secondPage.nextCursor());
    }

    @Test
    void historyRejectsABranchFilter() {
        RepositoryHandle handle = checkout();

        assertThrows(IllegalArgumentException.class, () -> provider.history(handle,
                new HistoryQuery(null, 10, "trunk", null, null, null, null, null)));
    }

    @Test
    void showLoadsOneRevisionsMetadata() {
        RepositoryHandle handle = checkout();

        Revision revision = provider.show(handle, new RevisionId("r1"));

        assertEquals("r1", revision.id().value());
        assertEquals("initial import", revision.message());
    }

    @Test
    void showRejectsAnUnknownRevision() {
        RepositoryHandle handle = checkout();

        assertThrows(NoSuchElementException.class, () -> provider.show(handle, new RevisionId("r999")));
    }

    private void commitDirectlyToServer(String fileName, String content) throws Exception {
        Path staging = Files.createTempDirectory(serverDir, "svn-direct-commit");
        SVNClientManager clients = SVNClientManager.newInstance();
        try {
            clients.getUpdateClient().doCheckout(repositoryUrl, staging.toFile(),
                    org.tmatesoft.svn.core.wc.SVNRevision.HEAD, org.tmatesoft.svn.core.wc.SVNRevision.HEAD,
                    org.tmatesoft.svn.core.SVNDepth.INFINITY, false);
            Files.writeString(staging.resolve(fileName), content);
            clients.getCommitClient().doCommit(new java.io.File[] { staging.toFile() }, false, "direct commit", null, null, false, false,
                    org.tmatesoft.svn.core.SVNDepth.INFINITY);
        } finally {
            clients.dispose();
        }
    }

    private RepositoryHandle checkout() {
        Path target = checkoutDir.resolve("wc");
        return provider.cloneRepository(new CloneRequest(repositoryUrl.toString(), target, null), OperationMonitor.noop());
    }
}

package dev.configflow.infrastructure.git;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.configflow.domain.vcs.exception.MergeConflictException;
import dev.configflow.domain.vcs.exception.VcsPreconditionException;
import dev.configflow.domain.vcs.model.CommitRequest;
import dev.configflow.domain.vcs.model.RepositoryHandle;
import dev.configflow.domain.vcs.model.VcsType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.NoSuchElementException;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.RepositoryState;
import org.eclipse.jgit.lib.StoredConfig;
import org.eclipse.jgit.revwalk.RevCommit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GitRebaseTest {

    private final GitRepositoryAccess access = new GitRepositoryAccess();
    private final GitRebase rebases = new GitRebase(access);
    private final GitWorkingTree workingTree = new GitWorkingTree(access);
    private final GitCommits commits = new GitCommits(access);
    private final GitBranches branches = new GitBranches(access);

    @TempDir
    Path repoDir;

    private RepositoryHandle handle;

    @BeforeEach
    void initRepo() throws Exception {
        try (Git git = Git.init().setDirectory(repoDir.toFile()).call()) {
            // Without this, Git rewrites LF to CRLF when it checks a file back out on
            // Windows, so an abort would "restore" content that no longer matches what
            // the test wrote.
            StoredConfig config = git.getRepository().getConfig();
            config.setString("core", null, "autocrlf", "false");
            config.save();
        }
        handle = new RepositoryHandle(repoDir, VcsType.GIT);

        write("base.txt", "base\n");
        commit("base.txt", "Initial commit");
    }

    private void write(String name, String content) throws Exception {
        Files.writeString(repoDir.resolve(name), content);
    }

    private void commit(String name, String message) {
        workingTree.stage(handle, List.of(Path.of(name)));
        commits.commit(handle, CommitRequest.of(message));
    }

    /**
     * Builds the classic rebase setup: {@code main} and {@code topic} both move on
     * from the initial commit, so replaying topic onto main is a real rebase rather
     * than a fast-forward.
     */
    private void divergeTopicFromMain(String topicContent, String mainContent) throws Exception {
        branches.createBranch(handle, "topic", null, true);
        write("topic.txt", topicContent);
        commit("topic.txt", "Topic work");

        branches.checkout(handle, "master");
        write("main.txt", mainContent);
        commit("main.txt", "Main work");

        branches.checkout(handle, "topic");
    }

    private List<String> messages() throws Exception {
        try (Git git = access.open(handle)) {
            return java.util.stream.StreamSupport
                    .stream(git.log().call().spliterator(), false)
                    .map(RevCommit::getShortMessage)
                    .toList();
        }
    }

    private RepositoryState state() throws Exception {
        try (Git git = access.open(handle)) {
            return git.getRepository().getRepositoryState();
        }
    }

    @Test
    void startReplaysTopicCommitsOnTopOfUpstream() throws Exception {
        divergeTopicFromMain("topic\n", "main\n");

        rebases.start(handle, "master");

        // After the rebase, topic's commit sits on top of main's — newest first.
        assertEquals(List.of("Topic work", "Main work", "Initial commit"), messages());
        assertTrue(Files.exists(repoDir.resolve("main.txt")));
        assertEquals(RepositoryState.SAFE, state());
    }

    @Test
    void startIsANoOpWhenAlreadyUpToDate() throws Exception {
        // No divergence: rebasing onto an ancestor has nothing to replay, and must
        // not be reported as a failure even though UP_TO_DATE is a distinct status.
        branches.createBranch(handle, "topic", null, true);

        rebases.start(handle, "master");

        assertEquals(List.of("Initial commit"), messages());
        assertEquals(RepositoryState.SAFE, state());
    }

    @Test
    void startRejectsUnknownUpstream() {
        assertThrows(NoSuchElementException.class, () -> rebases.start(handle, "no-such-branch"));
    }

    @Test
    void startRefusesToRunWithUncommittedChanges() throws Exception {
        divergeTopicFromMain("topic\n", "main\n");
        write("base.txt", "dirty\n");

        // Rebasing would silently discard the edit, so JGit reports
        // UNCOMMITTED_CHANGES and we translate it to a 409 rather than a 500.
        assertThrows(VcsPreconditionException.class, () -> rebases.start(handle, "master"));
    }

    @Test
    void startStopsOnConflictAndReportsThePaths() throws Exception {
        // Both branches change the same file, so replaying topic cannot apply cleanly.
        branches.createBranch(handle, "topic", null, true);
        write("base.txt", "topic version\n");
        commit("base.txt", "Topic edits base");

        branches.checkout(handle, "master");
        write("base.txt", "main version\n");
        commit("base.txt", "Main edits base");

        branches.checkout(handle, "topic");

        MergeConflictException conflict =
                assertThrows(MergeConflictException.class, () -> rebases.start(handle, "master"));

        // STOPPED carries no path list of its own, so this also proves the working
        // tree fallback in conflictedPaths() runs.
        assertEquals(List.of(Path.of("base.txt")), conflict.conflictedPaths());
        assertEquals(RepositoryState.REBASING_MERGE, state());
    }

    @Test
    void abortRestoresThePreRebaseState() throws Exception {
        branches.createBranch(handle, "topic", null, true);
        write("base.txt", "topic version\n");
        commit("base.txt", "Topic edits base");

        branches.checkout(handle, "master");
        write("base.txt", "main version\n");
        commit("base.txt", "Main edits base");

        branches.checkout(handle, "topic");
        assertThrows(MergeConflictException.class, () -> rebases.start(handle, "master"));

        // ABORTED reports isSuccessful() == false; a blanket success check here would
        // make every abort throw.
        rebases.abort(handle);

        assertEquals(RepositoryState.SAFE, state());
        assertEquals(List.of("Topic edits base", "Initial commit"), messages());
        assertEquals("topic version\n", Files.readString(repoDir.resolve("base.txt")));
    }

    @Test
    void continueFinishesTheRebaseOnceConflictsAreResolved() throws Exception {
        branches.createBranch(handle, "topic", null, true);
        write("base.txt", "topic version\n");
        commit("base.txt", "Topic edits base");

        branches.checkout(handle, "master");
        write("base.txt", "main version\n");
        commit("base.txt", "Main edits base");

        branches.checkout(handle, "topic");
        assertThrows(MergeConflictException.class, () -> rebases.start(handle, "master"));

        write("base.txt", "resolved\n");
        workingTree.stage(handle, List.of(Path.of("base.txt")));

        rebases.continueRebase(handle);

        assertEquals(RepositoryState.SAFE, state());
        assertEquals(List.of("Topic edits base", "Main edits base", "Initial commit"), messages());
        assertEquals("resolved\n", Files.readString(repoDir.resolve("base.txt")));
    }

    @Test
    void skipDropsTheConflictingCommit() throws Exception {
        branches.createBranch(handle, "topic", null, true);
        write("base.txt", "topic version\n");
        commit("base.txt", "Topic edits base");

        branches.checkout(handle, "master");
        write("base.txt", "main version\n");
        commit("base.txt", "Main edits base");

        branches.checkout(handle, "topic");
        assertThrows(MergeConflictException.class, () -> rebases.start(handle, "master"));

        rebases.skip(handle);

        assertEquals(RepositoryState.SAFE, state());
        // The skipped commit is gone; only main's history remains.
        assertFalse(messages().contains("Topic edits base"));
        assertEquals(List.of("Main edits base", "Initial commit"), messages());
    }

    @Test
    void statusReportsRebasingOnlyWhileTheRebaseIsPaused() throws Exception {
        branches.createBranch(handle, "topic", null, true);
        write("base.txt", "topic version\n");
        commit("base.txt", "Topic edits base");

        branches.checkout(handle, "master");
        write("base.txt", "main version\n");
        commit("base.txt", "Main edits base");

        branches.checkout(handle, "topic");
        assertFalse(workingTree.status(handle).rebasing());

        assertThrows(MergeConflictException.class, () -> rebases.start(handle, "master"));
        // This flag is what lets the UI offer continue / abort / skip; without it a
        // stopped rebase has no way out of the interface.
        assertTrue(workingTree.status(handle).rebasing());

        rebases.abort(handle);
        assertFalse(workingTree.status(handle).rebasing());
    }

    @Test
    void continueFailsWhenNoRebaseIsInProgress() {
        // WrongRepositoryStateException is a child of GitAPIException; catching it
        // after the general handler would surface this as a 500 instead of a 409.
        assertThrows(VcsPreconditionException.class, () -> rebases.continueRebase(handle));
    }

    @Test
    void abortFailsWhenNoRebaseIsInProgress() {
        assertThrows(VcsPreconditionException.class, () -> rebases.abort(handle));
    }

    @Test
    void skipFailsWhenNoRebaseIsInProgress() {
        assertThrows(VcsPreconditionException.class, () -> rebases.skip(handle));
    }

    @Test
    void rebaseLeavesOtherBranchesAlone() throws Exception {
        divergeTopicFromMain("topic\n", "main\n");

        rebases.start(handle, "master");

        try (Git git = access.open(handle)) {
            Repository repository = git.getRepository();
            assertTrue(repository.findRef("refs/heads/master") != null);
            assertEquals("refs/heads/topic", repository.getFullBranch());
        }
    }
}

package dev.configflow.infrastructure.git;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.configflow.domain.vcs.exception.MergeConflictException;
import dev.configflow.domain.vcs.exception.VcsPreconditionException;
import dev.configflow.domain.vcs.model.CommitRequest;
import dev.configflow.domain.vcs.model.RepositoryHandle;
import dev.configflow.domain.vcs.model.RevisionId;
import dev.configflow.domain.vcs.model.VcsType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.NoSuchElementException;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.RepositoryState;
import org.eclipse.jgit.lib.StoredConfig;
import org.eclipse.jgit.revwalk.RevCommit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GitRevertTest {

    private final GitRepositoryAccess access = new GitRepositoryAccess();
    private final GitRevert reverts = new GitRevert(access);
    private final GitWorkingTree workingTree = new GitWorkingTree(access);
    private final GitCommits commits = new GitCommits(access);
    private final GitBranches branches = new GitBranches(access);

    @TempDir
    Path repoDir;

    private RepositoryHandle handle;

    @BeforeEach
    void initRepo() throws Exception {
        try (Git git = Git.init().setDirectory(repoDir.toFile()).call()) {
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

    private RevisionId commit(String name, String message) {
        workingTree.stage(handle, List.of(Path.of(name)));
        return commits.commit(handle, CommitRequest.of(message));
    }

    private List<String> messages() throws Exception {
        try (Git git = access.open(handle)) {
            return java.util.stream.StreamSupport.stream(git.log().call().spliterator(), false)
                    .map(RevCommit::getShortMessage)
                    .toList();
        }
    }

    private RepositoryState state() throws Exception {
        try (Git git = access.open(handle)) {
            return git.getRepository().getRepositoryState();
        }
    }

    private String read(String name) throws Exception {
        return Files.readString(repoDir.resolve(name));
    }

    @Test
    void revertRecordsTheInverseOfASingleCommit() throws Exception {
        write("base.txt", "changed\n");
        RevisionId changed = commit("base.txt", "Change base");

        reverts.revert(handle, List.of(changed));

        assertEquals("base\n", read("base.txt"));
        assertEquals(
                List.of("Revert \"Change base\"", "Change base", "Initial commit"), messages());
        assertEquals(RepositoryState.SAFE, state());
    }

    @Test
    void revertUndoesMultipleRevisionsInRequestOrder() throws Exception {
        write("base.txt", "first\n");
        RevisionId first = commit("base.txt", "First change");
        write("base.txt", "second\n");
        RevisionId second = commit("base.txt", "Second change");

        reverts.revert(handle, List.of(second, first));

        assertEquals("base\n", read("base.txt"));
    }

    @Test
    void revertRejectsAnUnknownRevision() {
        assertThrows(
                NoSuchElementException.class,
                () -> reverts.revert(handle, List.of(new RevisionId("no-such-revision"))));
    }

    @Test
    void revertRejectsAWellFormedShaThatNamesNoObject() {
        assertThrows(
                NoSuchElementException.class,
                () -> reverts.revert(
                        handle, List.of(new RevisionId("0123456789012345678901234567890123456789"))));
    }

    @Test
    void revertStopsOnConflictAndReportsThePaths() throws Exception {
        branches.createBranch(handle, "topic", null, true);
        write("base.txt", "topic version\n");
        RevisionId topicCommit = commit("base.txt", "Topic edits base");

        branches.checkout(handle, "master");
        write("base.txt", "main version\n");
        commit("base.txt", "Main edits base");

        MergeConflictException conflict =
                assertThrows(MergeConflictException.class, () -> reverts.revert(handle, List.of(topicCommit)));

        assertEquals(List.of(Path.of("base.txt")), conflict.conflictedPaths());
        assertEquals(RepositoryState.REVERTING, state());
    }

    @Test
    void revertFailsWhenTheTargetFileHasUncommittedChanges() throws Exception {
        write("base.txt", "changed\n");
        RevisionId changed = commit("base.txt", "Change base");
        // Dirties base.txt without committing; revert must touch this same path.
        write("base.txt", "dirty\n");

        assertThrows(VcsPreconditionException.class, () -> reverts.revert(handle, List.of(changed)));
    }
}

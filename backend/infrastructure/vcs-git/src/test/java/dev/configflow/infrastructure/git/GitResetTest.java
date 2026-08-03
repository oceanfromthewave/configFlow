package dev.configflow.infrastructure.git;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.configflow.domain.vcs.model.CommitRequest;
import dev.configflow.domain.vcs.model.RepositoryHandle;
import dev.configflow.domain.vcs.model.ResetMode;
import dev.configflow.domain.vcs.model.RevisionId;
import dev.configflow.domain.vcs.model.VcsType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.NoSuchElementException;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.Status;
import org.eclipse.jgit.lib.StoredConfig;
import org.eclipse.jgit.revwalk.RevCommit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GitResetTest {

    private final GitRepositoryAccess access = new GitRepositoryAccess();
    private final GitReset resets = new GitReset(access);
    private final GitWorkingTree workingTree = new GitWorkingTree(access);
    private final GitCommits commits = new GitCommits(access);

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
    }

    private void write(String name, String content) throws Exception {
        Files.writeString(repoDir.resolve(name), content);
    }

    private RevisionId commit(String name, String message) {
        workingTree.stage(handle, List.of(Path.of(name)));
        return commits.commit(handle, CommitRequest.of(message));
    }

    private String read(String name) throws Exception {
        return Files.readString(repoDir.resolve(name));
    }

    private List<String> messages() throws Exception {
        try (Git git = access.open(handle)) {
            return java.util.stream.StreamSupport.stream(git.log().call().spliterator(), false)
                    .map(RevCommit::getShortMessage)
                    .toList();
        }
    }

    private Status status() throws Exception {
        try (Git git = access.open(handle)) {
            return git.status().call();
        }
    }

    @Test
    void resetSoftMovesTheBranchButLeavesIndexAndWorkingTreeUntouched() throws Exception {
        write("base.txt", "base\n");
        RevisionId initial = commit("base.txt", "Initial commit");
        write("base.txt", "changed\n");
        commit("base.txt", "Change base");

        resets.reset(handle, initial, ResetMode.SOFT);

        assertEquals("changed\n", read("base.txt"));
        assertEquals(List.of("Initial commit"), messages());
        assertTrue(status().getChanged().contains("base.txt"), "the undone change stays staged");
        assertTrue(status().getModified().isEmpty());
    }

    @Test
    void resetMixedMovesTheBranchAndUnstagesButKeepsTheWorkingTree() throws Exception {
        write("base.txt", "base\n");
        RevisionId initial = commit("base.txt", "Initial commit");
        write("base.txt", "changed\n");
        commit("base.txt", "Change base");

        resets.reset(handle, initial, ResetMode.MIXED);

        assertEquals("changed\n", read("base.txt"));
        assertEquals(List.of("Initial commit"), messages());
        assertTrue(status().getChanged().isEmpty());
        assertTrue(status().getModified().contains("base.txt"), "the undone change becomes an unstaged edit");
    }

    @Test
    void resetHardMovesTheBranchAndDiscardsTheWorkingTreeChange() throws Exception {
        write("base.txt", "base\n");
        RevisionId initial = commit("base.txt", "Initial commit");
        write("base.txt", "changed\n");
        commit("base.txt", "Change base");

        resets.reset(handle, initial, ResetMode.HARD);

        assertEquals("base\n", read("base.txt"));
        assertEquals(List.of("Initial commit"), messages());
        assertTrue(status().isClean());
    }

    @Test
    void resetRejectsAnUnknownRevision() throws Exception {
        write("base.txt", "base\n");
        commit("base.txt", "Initial commit");

        assertThrows(
                NoSuchElementException.class,
                () -> resets.reset(handle, new RevisionId("no-such-revision"), ResetMode.HARD));
    }

    @Test
    void resetRejectsAWellFormedShaThatNamesNoObject() throws Exception {
        write("base.txt", "base\n");
        commit("base.txt", "Initial commit");

        assertThrows(
                NoSuchElementException.class,
                () -> resets.reset(
                        handle,
                        new RevisionId("0123456789012345678901234567890123456789"),
                        ResetMode.HARD));
    }

    @Test
    void resetHardOverwritesAnUntrackedFileThatBlocksTheIncomingContent() throws Exception {
        // Unlike checkout, JGit's hard reset does not protect an untracked file from being
        // clobbered by the incoming commit's version of the same path — confirmed by running
        // this test, not assumed: real git refuses in this situation, JGit 7.3 does not.
        write("base.txt", "base\n");
        RevisionId initial = commit("base.txt", "Initial commit");
        write("new.txt", "tracked\n");
        RevisionId withNewFile = commit("new.txt", "Add new file");
        resets.reset(handle, initial, ResetMode.HARD);
        write("new.txt", "local untracked content\n");

        resets.reset(handle, withNewFile, ResetMode.HARD);

        assertEquals("tracked\n", read("new.txt"));
    }
}

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

class GitCherryPickTest {

    private final GitRepositoryAccess access = new GitRepositoryAccess();
    private final GitCherryPick cherryPicks = new GitCherryPick(access);
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

    @Test
    void cherryPickReplaysASingleCommitOntoTheCurrentBranch() throws Exception {
        branches.createBranch(handle, "topic", null, true);
        write("topic.txt", "topic content\n");
        RevisionId topicCommit = commit("topic.txt", "Topic addition");

        branches.checkout(handle, "master");
        cherryPicks.cherryPick(handle, List.of(topicCommit));

        assertEquals(List.of("Topic addition", "Initial commit"), messages());
        assertTrue(Files.exists(repoDir.resolve("topic.txt")));
        assertEquals(RepositoryState.SAFE, state());
    }

    @Test
    void cherryPickReplaysMultipleRevisionsInRequestOrder() throws Exception {
        branches.createBranch(handle, "topic", null, true);
        write("first.txt", "first\n");
        RevisionId first = commit("first.txt", "First topic commit");
        write("second.txt", "second\n");
        RevisionId second = commit("second.txt", "Second topic commit");

        branches.checkout(handle, "master");
        cherryPicks.cherryPick(handle, List.of(first, second));

        assertEquals(
                List.of("Second topic commit", "First topic commit", "Initial commit"), messages());
    }

    @Test
    void cherryPickRejectsAnUnknownRevision() {
        assertThrows(
                NoSuchElementException.class,
                () -> cherryPicks.cherryPick(handle, List.of(new RevisionId("no-such-revision"))));
    }

    @Test
    void cherryPickRejectsAWellFormedShaThatNamesNoObject() {
        // Unlike "no-such-revision" above, a full 40-hex SHA resolves to an ObjectId
        // without a database lookup; the miss only surfaces once the walk tries to
        // read it, as a MissingObjectException rather than a null resolve().
        assertThrows(
                NoSuchElementException.class,
                () -> cherryPicks.cherryPick(
                        handle, List.of(new RevisionId("0123456789012345678901234567890123456789"))));
    }

    @Test
    void cherryPickStopsOnConflictAndReportsThePaths() throws Exception {
        branches.createBranch(handle, "topic", null, true);
        write("base.txt", "topic version\n");
        RevisionId topicCommit = commit("base.txt", "Topic edits base");

        branches.checkout(handle, "master");
        write("base.txt", "main version\n");
        commit("base.txt", "Main edits base");

        MergeConflictException conflict = assertThrows(
                MergeConflictException.class,
                () -> cherryPicks.cherryPick(handle, List.of(topicCommit)));

        assertEquals(List.of(Path.of("base.txt")), conflict.conflictedPaths());
        // JGit leaves CHERRY_PICK_HEAD behind since there is no cherry-pick --continue;
        // the working tree stays conflicted for the user to resolve and commit manually.
        assertEquals(RepositoryState.CHERRY_PICKING, state());
    }

    @Test
    void cherryPickFailsWhenTheTargetFileHasUncommittedChanges() throws Exception {
        branches.createBranch(handle, "topic", null, true);
        write("base.txt", "topic version\n");
        RevisionId topicCommit = commit("base.txt", "Topic edits base");

        branches.checkout(handle, "master");
        // Dirties base.txt without committing; cherry-pick must touch this same path.
        write("base.txt", "dirty\n");

        assertThrows(
                VcsPreconditionException.class,
                () -> cherryPicks.cherryPick(handle, List.of(topicCommit)));
    }
}

package dev.configflow.infrastructure.git;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.configflow.domain.vcs.exception.MergeConflictException;
import dev.configflow.domain.vcs.model.CommitRequest;
import dev.configflow.domain.vcs.model.ConflictedFile;
import dev.configflow.domain.vcs.model.MergeRequest;
import dev.configflow.domain.vcs.model.RepositoryHandle;
import dev.configflow.domain.vcs.model.ThreeWayContent;
import dev.configflow.domain.vcs.model.VcsType;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.NoSuchElementException;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GitConflictsTest {

    private final GitRepositoryAccess access = new GitRepositoryAccess();
    private final GitConflicts conflicts = new GitConflicts(access);
    private final GitBranches branches = new GitBranches(access);
    private final GitCommits commits = new GitCommits(access);
    private final GitWorkingTree workingTree = new GitWorkingTree(access);

    @TempDir
    Path repoDir;

    private RepositoryHandle handle;
    private String mainBranch;

    @BeforeEach
    void initRepo() throws Exception {
        Git.init().setDirectory(repoDir.toFile()).call().close();
        handle = new RepositoryHandle(repoDir, VcsType.GIT);
        try (Git git = Git.open(repoDir.toFile())) {
            var config = git.getRepository().getConfig();
            config.setString("user", null, "name", "Test");
            config.setString("user", null, "email", "test@configflow.dev");
            config.setString("core", null, "autocrlf", "false");
            config.save();
        }
        commitFile("base.txt", "base\n");
        mainBranch = currentBranch();
    }

    /** Modify/modify conflict: both sides edit a path that already existed at the fork point. */
    private void createModifyModifyConflict() throws Exception {
        branches.createBranch(handle, "feature/x", null, true);
        commitFile("base.txt", "feature version\n");
        branches.checkout(handle, mainBranch);
        commitFile("base.txt", "main version\n");

        assertThrows(MergeConflictException.class,
                () -> branches.merge(handle, new MergeRequest("feature/x", false, false)));
    }

    @Test
    void listConflictsReportsTheConflictedPathAsUnresolved() throws Exception {
        createModifyModifyConflict();

        List<ConflictedFile> found = conflicts.listConflicts(handle);

        assertEquals(List.of(ConflictedFile.unresolved(Path.of("base.txt"))), found);
    }

    @Test
    void listConflictsIsEmptyWhenNothingIsConflicted() {
        assertEquals(List.of(), conflicts.listConflicts(handle));
    }

    @Test
    void threeWayContentReturnsAllThreeSidesForAModifyModifyConflict() throws Exception {
        createModifyModifyConflict();

        ThreeWayContent content = conflicts.threeWayContent(handle, Path.of("base.txt"));

        assertEquals(new ThreeWayContent("base\n", "main version\n", "feature version\n"), content);
    }

    @Test
    void threeWayContentHasNoBaseForAnAddAddConflict() throws Exception {
        branches.createBranch(handle, "feature/x", null, true);
        commitFile("new.txt", "from feature\n");
        branches.checkout(handle, mainBranch);
        commitFile("new.txt", "from main\n");
        assertThrows(MergeConflictException.class,
                () -> branches.merge(handle, new MergeRequest("feature/x", false, false)));

        ThreeWayContent content = conflicts.threeWayContent(handle, Path.of("new.txt"));

        assertEquals(new ThreeWayContent(null, "from main\n", "from feature\n"), content);
    }

    @Test
    void threeWayContentRejectsAPathThatIsNotConflicted() throws Exception {
        createModifyModifyConflict();

        assertThrows(NoSuchElementException.class,
                () -> conflicts.threeWayContent(handle, Path.of("no-such-file.txt")));
    }

    @Test
    void resolveMineKeepsTheCurrentBranchesContentAndClearsTheConflict() throws Exception {
        createModifyModifyConflict();

        conflicts.resolve(handle, Path.of("base.txt"), ConflictedFile.Resolution.MINE, null);

        assertEquals("main version\n", Files.readString(repoDir.resolve("base.txt")));
        assertTrue(conflicts.listConflicts(handle).isEmpty());
        assertTrue(status().getConflicting().isEmpty());
    }

    @Test
    void resolveTheirsTakesTheIncomingContentAndClearsTheConflict() throws Exception {
        createModifyModifyConflict();

        conflicts.resolve(handle, Path.of("base.txt"), ConflictedFile.Resolution.THEIRS, null);

        assertEquals("feature version\n", Files.readString(repoDir.resolve("base.txt")));
        assertTrue(conflicts.listConflicts(handle).isEmpty());
    }

    @Test
    void resolveManualWritesTheGivenContentAndClearsTheConflict() throws Exception {
        createModifyModifyConflict();

        conflicts.resolve(handle, Path.of("base.txt"), ConflictedFile.Resolution.MANUAL, "merged by hand\n");

        assertEquals("merged by hand\n", Files.readString(repoDir.resolve("base.txt")));
        assertTrue(conflicts.listConflicts(handle).isEmpty());
    }

    @Test
    void resolveManualRecreatesAParentDirectoryDeletedByTheMineSide() throws Exception {
        // If "mine" deleted the last file in a directory, the directory itself is gone
        // from the working tree; a manual resolution writing back into it must not 500.
        Files.createDirectories(repoDir.resolve("sub"));
        commitFile("sub/nested.txt", "base\n");
        branches.createBranch(handle, "feature/x", null, true);
        commitFile("sub/nested.txt", "edited on feature\n");
        branches.checkout(handle, mainBranch);
        Files.delete(repoDir.resolve("sub/nested.txt"));
        Files.delete(repoDir.resolve("sub"));
        workingTree.stage(handle, List.of(Path.of("sub/nested.txt")));
        commits.commit(handle, CommitRequest.of("delete sub/nested.txt"));

        assertThrows(MergeConflictException.class,
                () -> branches.merge(handle, new MergeRequest("feature/x", false, false)));

        conflicts.resolve(handle, Path.of("sub/nested.txt"), ConflictedFile.Resolution.MANUAL, "merged by hand\n");

        assertEquals("merged by hand\n", Files.readString(repoDir.resolve("sub/nested.txt")));
        assertTrue(conflicts.listConflicts(handle).isEmpty());
    }

    @Test
    void resolveRejectsUnresolvedAsAnExplicitChoice() throws Exception {
        createModifyModifyConflict();

        assertThrows(IllegalArgumentException.class,
                () -> conflicts.resolve(handle, Path.of("base.txt"), ConflictedFile.Resolution.UNRESOLVED, null));
    }

    @Test
    void resolveManualRequiresContent() throws Exception {
        createModifyModifyConflict();

        assertThrows(IllegalArgumentException.class,
                () -> conflicts.resolve(handle, Path.of("base.txt"), ConflictedFile.Resolution.MANUAL, null));
    }

    @Test
    void resolveRejectsAPathThatIsNotConflicted() throws Exception {
        createModifyModifyConflict();

        assertThrows(NoSuchElementException.class,
                () -> conflicts.resolve(handle, Path.of("no-such-file.txt"), ConflictedFile.Resolution.MINE, null));
    }

    @Test
    void resolveTheirsOnADeleteModifyConflictTakesTheSurvivingContent() throws Exception {
        // "mine" deletes the path, "theirs" edits it: stage 2 (ours) is absent from the
        // index for this path, only stage 1 (base) and stage 3 (theirs) exist.
        branches.createBranch(handle, "feature/x", null, true);
        commitFile("base.txt", "edited on feature\n");
        branches.checkout(handle, mainBranch);
        Files.delete(repoDir.resolve("base.txt"));
        workingTree.stage(handle, List.of(Path.of("base.txt")));
        commits.commit(handle, CommitRequest.of("delete base.txt"));

        assertThrows(MergeConflictException.class,
                () -> branches.merge(handle, new MergeRequest("feature/x", false, false)));

        conflicts.resolve(handle, Path.of("base.txt"), ConflictedFile.Resolution.THEIRS, null);

        assertEquals("edited on feature\n", Files.readString(repoDir.resolve("base.txt")));
        assertTrue(conflicts.listConflicts(handle).isEmpty());
    }

    @Test
    void resolveMineOnADeleteModifyConflictKeepsTheDeletion() throws Exception {
        branches.createBranch(handle, "feature/x", null, true);
        commitFile("base.txt", "edited on feature\n");
        branches.checkout(handle, mainBranch);
        Files.delete(repoDir.resolve("base.txt"));
        workingTree.stage(handle, List.of(Path.of("base.txt")));
        commits.commit(handle, CommitRequest.of("delete base.txt"));

        assertThrows(MergeConflictException.class,
                () -> branches.merge(handle, new MergeRequest("feature/x", false, false)));

        conflicts.resolve(handle, Path.of("base.txt"), ConflictedFile.Resolution.MINE, null);

        assertTrue(conflicts.listConflicts(handle).isEmpty());
        assertTrue(status().getConflicting().isEmpty());
    }

    private String currentBranch() throws IOException {
        try (Git git = Git.open(repoDir.toFile())) {
            return git.getRepository().getBranch();
        }
    }

    private void commitFile(String name, String content) throws Exception {
        Files.writeString(repoDir.resolve(name), content);
        workingTree.stage(handle, List.of(Path.of(name)));
        commits.commit(handle, CommitRequest.of("update " + name));
    }

    private org.eclipse.jgit.api.Status status() throws Exception {
        try (Git git = access.open(handle)) {
            return git.status().call();
        }
    }
}

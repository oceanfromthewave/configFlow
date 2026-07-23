package dev.configflow.infrastructure.git;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.configflow.domain.vcs.exception.MergeConflictException;
import dev.configflow.domain.vcs.exception.VcsPreconditionException;
import dev.configflow.domain.vcs.model.CommitRequest;
import dev.configflow.domain.vcs.model.MergeRequest;
import dev.configflow.domain.vcs.model.RefLabel;
import dev.configflow.domain.vcs.model.RepositoryHandle;
import dev.configflow.domain.vcs.model.RevisionId;
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

class GitBranchesTest {

    private final GitRepositoryAccess access = new GitRepositoryAccess();
    private final GitBranches branches = new GitBranches(access);
    private final GitRefs refs = new GitRefs(access);
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

    // --- checkout --------------------------------------------------------

    @Test
    void checkout_movesTheHead() throws Exception {
        branches.createBranch(handle, "feature/x", null, false);

        branches.checkout(handle, "feature/x");

        assertEquals("feature/x", currentBranch());
    }

    @Test
    void checkout_swapsTheWorkingTreeContent() throws Exception {
        branches.createBranch(handle, "feature/x", null, true);
        commitFile("only-on-feature.txt", "feature\n");

        branches.checkout(handle, mainBranch);

        assertFalse(Files.exists(repoDir.resolve("only-on-feature.txt")),
                "a file added on the branch must be gone on main");
    }

    @Test
    void checkout_acceptsARevisionAndDetachesTheHead() throws Exception {
        RevisionId first = commits.history(handle,
                dev.configflow.domain.vcs.model.HistoryQuery.firstPage(1)).items().get(0).id();
        commitFile("later.txt", "later\n");

        branches.checkout(handle, first.value());

        assertEquals(first.value(), currentBranch(), "detached head reports the id");
    }

    @Test
    void checkout_unknownRefIsNotFound() {
        assertThrows(NoSuchElementException.class,
                () -> branches.checkout(handle, "no-such-branch"));
    }

    @Test
    void checkout_refusesToOverwriteLocalChanges() throws Exception {
        branches.createBranch(handle, "feature/x", null, true);
        commitFile("shared.txt", "from feature\n");
        branches.checkout(handle, mainBranch);
        // Same path, uncommitted: switching back would silently destroy this.
        Files.writeString(repoDir.resolve("shared.txt"), "uncommitted work\n");

        VcsPreconditionException failure = assertThrows(VcsPreconditionException.class,
                () -> branches.checkout(handle, "feature/x"));

        assertTrue(failure.getMessage().contains("shared.txt"),
                () -> "the message should name the file in the way: " + failure.getMessage());
        assertEquals("uncommitted work\n", Files.readString(repoDir.resolve("shared.txt")));
    }

    // --- create ----------------------------------------------------------

    @Test
    void createBranch_addsItWithoutMovingTheHead() throws Exception {
        branches.createBranch(handle, "feature/x", null, false);

        assertTrue(branchNames().contains("feature/x"));
        assertEquals(mainBranch, currentBranch());
    }

    @Test
    void createBranch_canCheckOutImmediately() throws Exception {
        branches.createBranch(handle, "feature/x", null, true);

        assertEquals("feature/x", currentBranch());
    }

    @Test
    void createBranch_startsFromTheGivenPoint() throws Exception {
        RevisionId first = commits.history(handle,
                dev.configflow.domain.vcs.model.HistoryQuery.firstPage(1)).items().get(0).id();
        commitFile("later.txt", "later\n");

        branches.createBranch(handle, "from-first", first.value(), true);

        assertFalse(Files.exists(repoDir.resolve("later.txt")),
                "the branch should start before the later commit");
    }

    @Test
    void createBranch_rejectsADuplicateName() {
        branches.createBranch(handle, "feature/x", null, false);

        assertThrows(VcsPreconditionException.class,
                () -> branches.createBranch(handle, "feature/x", null, false));
    }

    @Test
    void createBranch_rejectsAnInvalidName() {
        assertThrows(IllegalArgumentException.class,
                () -> branches.createBranch(handle, "bad name", null, false));
    }

    @Test
    void createBranch_unknownStartPointIsNotFound() {
        assertThrows(NoSuchElementException.class,
                () -> branches.createBranch(handle, "feature/x", "no-such-ref", false));
    }

    // --- delete ----------------------------------------------------------

    @Test
    void deleteBranch_removesAMergedBranch() throws Exception {
        branches.createBranch(handle, "feature/x", null, false);

        branches.deleteBranch(handle, "feature/x", false, false);

        assertFalse(branchNames().contains("feature/x"));
    }

    @Test
    void deleteBranch_refusesToDiscardUnmergedWorkWithoutForce() throws Exception {
        branches.createBranch(handle, "feature/x", null, true);
        commitFile("unmerged.txt", "work\n");
        branches.checkout(handle, mainBranch);

        assertThrows(VcsPreconditionException.class,
                () -> branches.deleteBranch(handle, "feature/x", false, false));
        assertTrue(branchNames().contains("feature/x"));
    }

    @Test
    void deleteBranch_discardsUnmergedWorkWhenForced() throws Exception {
        branches.createBranch(handle, "feature/x", null, true);
        commitFile("unmerged.txt", "work\n");
        branches.checkout(handle, mainBranch);

        branches.deleteBranch(handle, "feature/x", false, true);

        assertFalse(branchNames().contains("feature/x"));
    }

    @Test
    void deleteBranch_unknownBranchIsNotFound() {
        // JGit answers with an empty list rather than failing, so a typo would otherwise
        // be reported to the user as a successful deletion.
        assertThrows(NoSuchElementException.class,
                () -> branches.deleteBranch(handle, "no-such-branch", false, false));
    }

    @Test
    void deleteBranch_refusesTheCheckedOutBranch() {
        assertThrows(VcsPreconditionException.class,
                () -> branches.deleteBranch(handle, mainBranch, false, false));
    }

    // --- merge -----------------------------------------------------------

    @Test
    void merge_fastForwardsWhenNothingDiverged() throws Exception {
        branches.createBranch(handle, "feature/x", null, true);
        commitFile("feature.txt", "feature\n");
        branches.checkout(handle, mainBranch);

        branches.merge(handle, new MergeRequest("feature/x", false, false));

        assertTrue(Files.exists(repoDir.resolve("feature.txt")));
    }

    @Test
    void merge_combinesDivergedBranches() throws Exception {
        branches.createBranch(handle, "feature/x", null, true);
        commitFile("feature.txt", "feature\n");
        branches.checkout(handle, mainBranch);
        commitFile("main.txt", "main\n");

        branches.merge(handle, new MergeRequest("feature/x", false, false));

        assertTrue(Files.exists(repoDir.resolve("feature.txt")));
        assertTrue(Files.exists(repoDir.resolve("main.txt")));
    }

    @Test
    void merge_reportsConflictedPathsAndLeavesThemForTheUser() throws Exception {
        branches.createBranch(handle, "feature/x", null, true);
        commitFile("shared.txt", "feature version\n");
        branches.checkout(handle, mainBranch);
        commitFile("shared.txt", "main version\n");

        MergeConflictException conflict = assertThrows(MergeConflictException.class,
                () -> branches.merge(handle, new MergeRequest("feature/x", false, false)));

        assertEquals(List.of(Path.of("shared.txt")), conflict.conflictedPaths());
        assertFalse(workingTree.status(handle).conflicted().isEmpty(),
                "the working tree stays conflicted so the user can resolve it");
    }

    @Test
    void merge_fastForwardOnlyFailsOnDivergedHistory() throws Exception {
        branches.createBranch(handle, "feature/x", null, true);
        commitFile("feature.txt", "feature\n");
        branches.checkout(handle, mainBranch);
        commitFile("main.txt", "main\n");

        // Not a server fault: the caller asked for fast-forward against a diverged
        // history, and can retry with a real merge.
        assertThrows(VcsPreconditionException.class,
                () -> branches.merge(handle, new MergeRequest("feature/x", true, false)));
    }

    @Test
    void merge_unknownSourceIsNotFound() {
        assertThrows(NoSuchElementException.class,
                () -> branches.merge(handle, new MergeRequest("no-such-branch", false, false)));
    }

    // --- fixture helpers -------------------------------------------------

    private List<String> branchNames() {
        return refs.listRefs(handle).stream()
                .filter(ref -> ref.kind() == RefLabel.Kind.BRANCH)
                .map(RefLabel::name)
                .toList();
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
}

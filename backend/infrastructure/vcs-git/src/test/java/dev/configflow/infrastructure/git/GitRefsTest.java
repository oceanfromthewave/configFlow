package dev.configflow.infrastructure.git;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.configflow.domain.vcs.model.CommitRequest;
import dev.configflow.domain.vcs.model.RefLabel;
import dev.configflow.domain.vcs.model.RepositoryHandle;
import dev.configflow.domain.vcs.model.Revision;
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

class GitRefsTest {

    private final GitRepositoryAccess access = new GitRepositoryAccess();
    private final GitRefs refs = new GitRefs(access);
    private final GitCommits commits = new GitCommits(access);
    private final GitWorkingTree workingTree = new GitWorkingTree(access);

    @TempDir
    Path repoDir;

    private RepositoryHandle handle;

    @BeforeEach
    void initRepo() throws Exception {
        Git.init().setDirectory(repoDir.toFile()).call().close();
        handle = new RepositoryHandle(repoDir, VcsType.GIT);
        try (Git git = Git.open(repoDir.toFile())) {
            var config = git.getRepository().getConfig();
            config.setString("user", null, "name", "Test");
            config.setString("user", null, "email", "test@configflow.dev");
            config.save();
        }
    }

    // --- listRefs --------------------------------------------------------

    @Test
    void listRefs_onRepositoryWithoutCommitsIsEmpty() {
        // An unborn branch has nothing to point at, not even HEAD.
        assertTrue(refs.listRefs(handle).isEmpty());
    }

    @Test
    void listRefs_namesTheCurrentBranchThroughTheHeadEntry() throws Exception {
        commitFile("a.txt", "1");
        createBranch("feature/x");

        List<RefLabel> all = refs.listRefs(handle);

        assertEquals(1, countOf(all, RefLabel.Kind.HEAD), "exactly one HEAD entry");
        assertEquals(currentBranch(), headNameOf(all));
        assertTrue(namesOf(all, RefLabel.Kind.BRANCH).contains("feature/x"));
    }

    @Test
    void listRefs_followsTheHeadAfterCheckout() throws Exception {
        commitFile("a.txt", "1");
        createBranch("feature/x");
        try (Git git = Git.open(repoDir.toFile())) {
            git.checkout().setName("feature/x").call();
        }

        assertEquals("feature/x", headNameOf(refs.listRefs(handle)));
    }

    @Test
    void listRefs_reportsADetachedHeadAsTheRevisionId() throws Exception {
        RevisionId first = commitFile("a.txt", "1");
        commitFile("b.txt", "2");
        try (Git git = Git.open(repoDir.toFile())) {
            git.checkout().setName(first.value()).call();
        }

        // getBranch() returns the id when HEAD is not on a branch, which is the contract.
        assertEquals(first.value(), headNameOf(refs.listRefs(handle)));
    }

    @Test
    void listRefs_separatesBranchesTagsAndRemoteBranches() throws Exception {
        commitFile("a.txt", "1");
        createBranch("feature/x");
        try (Git git = Git.open(repoDir.toFile())) {
            git.tag().setName("v1.0").call();
            // Fake a fetched remote branch without needing a network peer.
            git.getRepository().updateRef("refs/remotes/origin/main").link("refs/heads/master");
        }

        List<RefLabel> all = refs.listRefs(handle);

        assertTrue(namesOf(all, RefLabel.Kind.BRANCH).contains("feature/x"));
        assertEquals(List.of("v1.0"), namesOf(all, RefLabel.Kind.TAG));
        assertTrue(namesOf(all, RefLabel.Kind.REMOTE_BRANCH).contains("origin/main"));
    }

    @Test
    void listRefs_skipsTheRemoteHeadPointer() throws Exception {
        commitFile("a.txt", "1");
        try (Git git = Git.open(repoDir.toFile())) {
            git.getRepository().updateRef("refs/remotes/origin/main").link("refs/heads/master");
            // origin/HEAD only points at the remote's default branch; it is not a branch.
            git.getRepository().updateRef("refs/remotes/origin/HEAD")
                    .link("refs/remotes/origin/main");
        }

        List<String> remotes = namesOf(refs.listRefs(handle), RefLabel.Kind.REMOTE_BRANCH);

        assertTrue(remotes.contains("origin/main"));
        assertFalse(remotes.contains("origin/HEAD"), () -> "origin/HEAD leaked into " + remotes);
    }

    // --- compare ---------------------------------------------------------

    @Test
    void compare_returnsOnlyWhatTheTargetAdds() throws Exception {
        commitFile("base.txt", "1");
        String main = currentBranch();
        createBranch("feature/x");
        try (Git git = Git.open(repoDir.toFile())) {
            git.checkout().setName("feature/x").call();
        }
        commitFile("f1.txt", "1");
        commitFile("f2.txt", "2");

        List<Revision> ahead = refs.compare(handle, main, "feature/x");

        assertEquals(List.of("update f2.txt", "update f1.txt"),
                ahead.stream().map(r -> r.message().trim()).toList());
    }

    @Test
    void compare_isDirectional() throws Exception {
        commitFile("base.txt", "1");
        String main = currentBranch();
        createBranch("feature/x");
        try (Git git = Git.open(repoDir.toFile())) {
            git.checkout().setName("feature/x").call();
        }
        commitFile("f1.txt", "1");

        // The branch is ahead of main, so main adds nothing on top of the branch.
        assertEquals(1, refs.compare(handle, main, "feature/x").size());
        assertTrue(refs.compare(handle, "feature/x", main).isEmpty());
    }

    @Test
    void compare_ofARefWithItselfIsEmpty() throws Exception {
        commitFile("a.txt", "1");
        String main = currentBranch();

        assertTrue(refs.compare(handle, main, main).isEmpty());
    }

    @Test
    void compare_unknownRefIsNotFound() throws Exception {
        commitFile("a.txt", "1");
        String main = currentBranch();

        assertThrows(NoSuchElementException.class,
                () -> refs.compare(handle, main, "no-such-branch"));
        assertThrows(NoSuchElementException.class,
                () -> refs.compare(handle, "no-such-branch", main));
    }

    @Test
    void compare_malformedRefIsRejectedAsBadInput() throws Exception {
        commitFile("a.txt", "1");
        String main = currentBranch();

        assertThrows(IllegalArgumentException.class, () -> refs.compare(handle, main, "a b"));
        assertThrows(IllegalArgumentException.class, () -> refs.compare(handle, "HEAD^{", main));
    }

    // --- fixture helpers -------------------------------------------------

    private static long countOf(List<RefLabel> refs, RefLabel.Kind kind) {
        return refs.stream().filter(r -> r.kind() == kind).count();
    }

    private static String headNameOf(List<RefLabel> refs) {
        return refs.stream()
                .filter(r -> r.kind() == RefLabel.Kind.HEAD)
                .map(RefLabel::name)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no HEAD entry in " + refs));
    }

    private static List<String> namesOf(List<RefLabel> refs, RefLabel.Kind kind) {
        return refs.stream().filter(r -> r.kind() == kind).map(RefLabel::name).toList();
    }

    /** JGit's default initial branch name varies, so the tests ask instead of assuming. */
    private String currentBranch() throws IOException {
        try (Git git = Git.open(repoDir.toFile())) {
            return git.getRepository().getBranch();
        }
    }

    private void createBranch(String name) throws Exception {
        try (Git git = Git.open(repoDir.toFile())) {
            git.branchCreate().setName(name).call();
        }
    }

    private RevisionId commitFile(String name, String content) throws Exception {
        Files.writeString(repoDir.resolve(name), content);
        workingTree.stage(handle, List.of(Path.of(name)));
        return commits.commit(handle, CommitRequest.of("update " + name));
    }
}

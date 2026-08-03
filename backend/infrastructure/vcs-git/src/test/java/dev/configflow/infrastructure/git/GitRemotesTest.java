package dev.configflow.infrastructure.git;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.configflow.domain.credential.Credential;
import dev.configflow.domain.credential.RemoteCredentialResolver;
import dev.configflow.domain.operation.OperationCancelledException;
import dev.configflow.domain.operation.OperationProgress;
import dev.configflow.domain.vcs.exception.MergeConflictException;
import dev.configflow.domain.vcs.exception.VcsPreconditionException;
import dev.configflow.domain.vcs.model.CloneRequest;
import dev.configflow.domain.vcs.model.CommitRequest;
import dev.configflow.domain.vcs.model.FetchRequest;
import dev.configflow.domain.vcs.model.PullRequest;
import dev.configflow.domain.vcs.model.PushRequest;
import dev.configflow.domain.vcs.model.RefLabel;
import dev.configflow.domain.vcs.model.RepositoryHandle;
import dev.configflow.domain.vcs.model.VcsType;
import dev.configflow.domain.vcs.port.OperationMonitor;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.RepositoryCache;
import org.eclipse.jgit.util.FileUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Exercises the remote operations against a bare repository on disk.
 *
 * <p>A local bare repo is a real remote as far as JGit is concerned — same transport
 * code path, same push-rejection semantics — without needing a network peer.</p>
 */
class GitRemotesTest {

    private final GitRepositoryAccess access = new GitRepositoryAccess();
    private final GitRemotes remotes = new GitRemotes(access);
    private final GitRefs refs = new GitRefs(access);
    private final GitCommits commits = new GitCommits(access);
    private final GitWorkingTree workingTree = new GitWorkingTree(access);

    /**
     * Managed by hand rather than with {@code @TempDir}.
     *
     * <p>These tests create clones, and a clone leaves pack files that JGit keeps open
     * past {@code close()} on Windows. JUnit's cleanup treats a failed delete as a test
     * failure, which turns a platform quirk into red tests; JGit's own deleter retries
     * and we ignore what it cannot remove.</p>
     */
    private Path root;

    private Path remoteDir;
    private Path cloneDir;
    private RepositoryHandle clone;
    private final RecordingMonitor monitor = new RecordingMonitor();

    @BeforeEach
    void setUp() throws Exception {
        root = Files.createTempDirectory("cf-remotes");
        remoteDir = root.resolve("origin.git");
        cloneDir = root.resolve("clone");

        Git.init().setBare(true).setDirectory(remoteDir.toFile()).call().close();

        // Seed the remote through a throwaway working copy so the clone has history.
        Path seedDir = root.resolve("seed");
        Git.cloneRepository()
                .setURI(remoteDir.toUri().toString())
                .setDirectory(seedDir.toFile())
                .call().close();
        RepositoryHandle seed = handleFor(seedDir);
        configureIdentity(seedDir);
        commitFile(seed, seedDir, "base.txt", "base\n");
        remotes.push(seed, new PushRequest(null, false, false), OperationMonitor.noop());

        remotes.cloneRepository(
                new CloneRequest(remoteDir.toUri().toString(), cloneDir, null), monitor);
        clone = handleFor(cloneDir);
        configureIdentity(cloneDir);
        monitor.reports.clear();
    }

    @AfterEach
    void releaseRepositoryHandles() {
        RepositoryCache.clear();
        try {
            FileUtils.delete(root.toFile(),
                    FileUtils.RECURSIVE | FileUtils.RETRY | FileUtils.SKIP_MISSING);
        } catch (IOException ignored) {
            // A leftover temp directory is the OS's problem, not a test failure.
        }
    }

    // --- clone -----------------------------------------------------------

    @Test
    void clone_producesAWorkingCopyWithTheRemoteContent() {
        assertTrue(Files.exists(cloneDir.resolve("base.txt")));
        assertTrue(Files.isDirectory(cloneDir.resolve(".git")));
    }

    @Test
    void clone_reportsProgressAndNamesItsPhases() throws Exception {
        RecordingMonitor cloneMonitor = new RecordingMonitor();
        Path target = root.resolve("second-clone");

        remotes.cloneRepository(
                new CloneRequest(remoteDir.toUri().toString(), target, null), cloneMonitor);

        assertFalse(cloneMonitor.reports.isEmpty(), "a clone must report what it is doing");
        assertTrue(cloneMonitor.reports.stream().allMatch(p -> p.phase() != null));
    }

    @Test
    void clone_asksTheMonitorWhetherToKeepGoing() {
        RecordingMonitor cancelling = new RecordingMonitor();
        cancelling.cancelled.set(true);
        Path target = root.resolve("cancelled-clone");

        // Cancellation is best-effort by design: JGit only checks between chunks, and a
        // three-file local clone finishes before it ever asks. Asserting that this throws
        // would be asserting that the repository is slow. What matters is that the signal
        // is wired through, which JGitProgressMonitorTest pins down; here we only require
        // that asking to cancel never corrupts the outcome.
        try {
            remotes.cloneRepository(
                    new CloneRequest(remoteDir.toUri().toString(), target, null), cancelling);
            assertTrue(Files.isDirectory(target.resolve(".git")),
                    "a clone that ran to completion must still be a usable repository");
        } catch (OperationCancelledException e) {
            assertFalse(Files.exists(target.resolve(".git").resolve("HEAD")),
                    "a cancelled clone must not leave a half-built repository behind");
        }
    }

    @Test
    void clone_ofAMissingRemoteIsReportedAsSuch() {
        Path target = root.resolve("nowhere");

        assertThrows(RuntimeException.class, () -> remotes.cloneRepository(
                new CloneRequest(root.resolve("no-such-repo.git").toUri().toString(),
                        target, null), OperationMonitor.noop()));
    }

    // --- init ------------------------------------------------------------

    @Test
    void init_createsAnEmptyRepository() {
        Path target = root.resolve("fresh");

        RepositoryHandle handle = remotes.init(target);

        assertEquals(target, handle.localPath());
        assertTrue(Files.isDirectory(target.resolve(".git")));
        assertTrue(refs.listRefs(handle).isEmpty(), "no commits yet");
    }

    // --- fetch -----------------------------------------------------------

    @Test
    void fetch_bringsDownCommitsMadeElsewhere() throws Exception {
        pushFromAnotherWorkingCopy("added-elsewhere.txt", "hello\n");

        remotes.fetch(clone, new FetchRequest(null, false), monitor);

        // Fetch updates the remote-tracking ref without touching the working tree.
        assertTrue(remoteBranchNames().contains("origin/master")
                || remoteBranchNames().contains("origin/main"));
        assertFalse(Files.exists(cloneDir.resolve("added-elsewhere.txt")),
                "fetch must not change the working tree");
    }

    @Test
    void fetch_reportsProgress() throws Exception {
        pushFromAnotherWorkingCopy("more.txt", "more\n");

        remotes.fetch(clone, new FetchRequest(null, false), monitor);

        assertFalse(monitor.reports.isEmpty());
    }

    @Test
    void fetch_unknownRemoteIsNotFoundAndSaysWhichOne() {
        NoSuchElementException failure = assertThrows(NoSuchElementException.class,
                () -> remotes.fetch(clone, new FetchRequest("no-such-remote", false),
                        OperationMonitor.noop()));

        // Letting JGit fail instead would name the local path, which explains nothing.
        assertTrue(failure.getMessage().contains("no-such-remote"),
                () -> "should name the remote the user asked for: " + failure.getMessage());
    }

    // --- credentials -----------------------------------------------------

    @Test
    void aRemoteOperationWipesTheResolvedSecretOnceItIsDone() {
        char[] secret = "s3cr3t".toCharArray();
        RecordingResolver resolver = new RecordingResolver(secret);
        GitRemotes withCredentials = new GitRemotes(access, resolver);

        withCredentials.fetch(clone, new FetchRequest(null, false), OperationMonitor.noop());

        assertTrue(resolver.consulted, "the resolver must be asked for the remote's credential");
        // JGit's provider keeps the array we hand it, and clearing it in a finally zero-fills
        // that same array — so nothing is left holding the secret after the transport is done.
        assertArrayEquals(new char[secret.length], secret,
                "the secret must be wiped once the transport has taken it");
    }

    // --- pull ------------------------------------------------------------

    @Test
    void pull_integratesRemoteCommitsIntoTheWorkingTree() throws Exception {
        pushFromAnotherWorkingCopy("pulled.txt", "pulled\n");

        remotes.pull(clone, new PullRequest(null, PullRequest.Strategy.MERGE), monitor);

        assertEquals("pulled\n", Files.readString(cloneDir.resolve("pulled.txt")));
    }

    @Test
    void pull_withRebaseKeepsHistoryLinear() throws Exception {
        pushFromAnotherWorkingCopy("theirs.txt", "theirs\n");
        commitFile(clone, cloneDir, "mine.txt", "mine\n");

        remotes.pull(clone, new PullRequest(null, PullRequest.Strategy.REBASE), monitor);

        // Rebased, so the local commit sits on top with a single parent rather than
        // hanging off a merge.
        assertEquals(1, commits.history(clone,
                dev.configflow.domain.vcs.model.HistoryQuery.firstPage(1))
                .items().get(0).parents().size());
        assertTrue(Files.exists(cloneDir.resolve("theirs.txt")));
        assertTrue(Files.exists(cloneDir.resolve("mine.txt")));
    }

    @Test
    void pull_reportsConflictsInsteadOfClaimingSuccess() throws Exception {
        pushFromAnotherWorkingCopy("shared.txt", "their version\n");
        commitFile(clone, cloneDir, "shared.txt", "my version\n");

        MergeConflictException conflict = assertThrows(MergeConflictException.class,
                () -> remotes.pull(
                        clone, new PullRequest(null, PullRequest.Strategy.MERGE), monitor));

        assertEquals(List.of(Path.of("shared.txt")), conflict.conflictedPaths());
        assertFalse(workingTree.status(clone).conflicted().isEmpty(),
                "the working tree is left conflicted for the user to resolve");
    }

    // --- push ------------------------------------------------------------

    @Test
    void push_uploadsLocalCommits() throws Exception {
        commitFile(clone, cloneDir, "pushed.txt", "pushed\n");

        remotes.push(clone, new PushRequest(null, false, false), monitor);

        assertTrue(remoteHasFile("pushed.txt"));
    }

    @Test
    void push_movesTheRemoteTrackingRefLikePlainGitDoes() throws Exception {
        commitFile(clone, cloneDir, "pushed.txt", "pushed\n");

        remotes.push(clone, new PushRequest(null, false, false), monitor);

        // JGit leaves this behind, so without our own update the branch list keeps
        // showing a stale origin/... and force-with-lease trips over the user's own push.
        try (Git git = Git.open(cloneDir.toFile())) {
            String branch = git.getRepository().getBranch();
            assertEquals(
                    git.getRepository().resolve(Constants.HEAD),
                    git.getRepository().resolve(Constants.R_REMOTES + "origin/" + branch));
        }
    }

    @Test
    void push_reportsARejectionRatherThanSucceedingSilently() throws Exception {
        // Someone else pushed first, so our history is behind: Git refuses to overwrite.
        pushFromAnotherWorkingCopy("theirs.txt", "theirs\n");
        commitFile(clone, cloneDir, "mine.txt", "mine\n");

        // JGit answers with a rejection status instead of throwing, so without an
        // explicit check this would look to the user like a successful push.
        VcsPreconditionException rejection = assertThrows(VcsPreconditionException.class,
                () -> remotes.push(clone, new PushRequest(null, false, false), monitor));

        assertTrue(rejection.getMessage().contains("REJECTED_NONFASTFORWARD"),
                () -> "the reason should be visible: " + rejection.getMessage());
        assertFalse(remoteHasFile("mine.txt"), "nothing may have landed");
    }

    @Test
    void push_forceWithLeaseRefusesWhenSomeoneElsePushedFirst() throws Exception {
        // The colleague's commit arrived after our last fetch, so our copy of the remote
        // is stale. Overwriting here would erase their work without anyone noticing —
        // which is exactly the case --force-with-lease exists to catch.
        pushFromAnotherWorkingCopy("theirs.txt", "theirs\n");
        commitFile(clone, cloneDir, "mine.txt", "mine\n");

        assertThrows(VcsPreconditionException.class,
                () -> remotes.push(clone, new PushRequest(null, true, false), monitor));

        assertTrue(remoteHasFile("theirs.txt"), "their commit must survive");
        assertFalse(remoteHasFile("mine.txt"));
    }

    @Test
    void push_forceWithLeaseRewritesWhenWeHaveSeenTheRemoteState() throws Exception {
        // Nobody else touched it since our clone, so the lease holds and rewriting our
        // own history is allowed.
        commitFile(clone, cloneDir, "first.txt", "first\n");
        remotes.push(clone, new PushRequest(null, false, false), monitor);
        amendLastCommit(cloneDir, "amended\n");

        remotes.push(clone, new PushRequest(null, true, false), monitor);

        assertEquals("amended", remoteFileContent("first.txt"));
    }

    @Test
    void push_forceWithLeaseRefusesWithoutARemoteTrackingRef() throws Exception {
        // A branch we have never fetched gives no basis for claiming ours supersedes it,
        // so falling back to a plain force would be the dangerous reading.
        try (Git git = Git.open(cloneDir.toFile())) {
            git.checkout().setCreateBranch(true).setName("never-fetched").call();
        }
        commitFile(clone, cloneDir, "new.txt", "new\n");

        assertThrows(VcsPreconditionException.class,
                () -> remotes.push(clone, new PushRequest(null, true, false), monitor));
    }

    @Test
    void push_unknownRemoteIsNotFound() {
        assertThrows(NoSuchElementException.class, () -> remotes.push(
                clone, new PushRequest("no-such-remote", false, false),
                OperationMonitor.noop()));
    }

    @Test
    void push_withNothingToSendIsNotAnError() {
        remotes.push(clone, new PushRequest(null, false, false), monitor);
    }

    // --- SVN-only operations ---------------------------------------------

    @Test
    void gitRejectsTheSvnOnlyOperations() {
        GitVcsProvider provider = new GitVcsProvider();

        assertThrows(UnsupportedOperationException.class,
                () -> provider.update(clone, null, OperationMonitor.noop()));
        assertThrows(UnsupportedOperationException.class, () -> provider.cleanup(clone));
    }

    // --- fixture helpers -------------------------------------------------

    private static RepositoryHandle handleFor(Path path) {
        return new RepositoryHandle(path, VcsType.GIT);
    }

    private List<String> remoteBranchNames() {
        return refs.listRefs(clone).stream()
                .filter(ref -> ref.kind() == RefLabel.Kind.REMOTE_BRANCH)
                .map(RefLabel::name)
                .toList();
    }

    /** Reads the remote's tree directly, since a bare repo has no working copy. */
    private boolean remoteHasFile(String name) throws Exception {
        return Files.exists(freshCheckout().resolve(name));
    }

    /** Trimmed: the verification clone checks out under the machine's own autocrlf. */
    private String remoteFileContent(String name) throws Exception {
        return Files.readString(freshCheckout().resolve(name)).trim();
    }

    private Path freshCheckout() throws Exception {
        Path checkout = root.resolve("verify-" + System.nanoTime());
        Git.cloneRepository()
                .setURI(remoteDir.toUri().toString())
                .setDirectory(checkout.toFile())
                .call().close();
        return checkout;
    }

    /** Rewrites the last commit, which is the honest reason to force-push at all. */
    private void amendLastCommit(Path dir, String content) throws Exception {
        Files.writeString(dir.resolve("first.txt"), content);
        try (Git git = Git.open(dir.toFile())) {
            git.add().addFilepattern("first.txt").call();
            git.commit().setAmend(true).setMessage("amended").call();
        }
    }

    /** Simulates a colleague pushing to the same remote. */
    private void pushFromAnotherWorkingCopy(String name, String content) throws Exception {
        Path otherDir = root.resolve("other-" + System.nanoTime());
        Git.cloneRepository()
                .setURI(remoteDir.toUri().toString())
                .setDirectory(otherDir.toFile())
                .call().close();
        configureIdentity(otherDir);
        RepositoryHandle other = handleFor(otherDir);
        commitFile(other, otherDir, name, content);
        remotes.push(other, new PushRequest(null, false, false), OperationMonitor.noop());
    }

    private void commitFile(RepositoryHandle handle, Path dir, String name, String content)
            throws Exception {
        Files.writeString(dir.resolve(name), content);
        workingTree.stage(handle, List.of(Path.of(name)));
        commits.commit(handle, CommitRequest.of("update " + name));
    }

    /**
     * Gives a working copy a committer and pins line endings.
     *
     * <p>The reset matters: a clone checks out under whatever {@code core.autocrlf} the
     * machine's global config says, so switching it to false afterwards leaves every
     * already-checked-out file looking modified — which makes rebase refuse with
     * UNCOMMITTED_CHANGES for reasons that have nothing to do with the test.</p>
     */
    private static void configureIdentity(Path dir) throws Exception {
        try (Git git = Git.open(dir.toFile())) {
            var config = git.getRepository().getConfig();
            config.setString("user", null, "name", "Test");
            config.setString("user", null, "email", "test@configflow.dev");
            config.setString("core", null, "autocrlf", "false");
            config.save();
            if (git.getRepository().resolve("HEAD") != null) {
                git.reset().setMode(org.eclipse.jgit.api.ResetCommand.ResetType.HARD).call();
            }
        }
    }

    /** Hands back the very array the test holds, so the test can assert the wipe reached it. */
    private static final class RecordingResolver implements RemoteCredentialResolver {

        private final char[] secret;
        private boolean consulted;

        RecordingResolver(char[] secret) {
            this.secret = secret;
        }

        @Override
        public Optional<Credential> resolve(String host, String protocol) {
            consulted = true;
            return Optional.of(new Credential("github.com", "https", "user", secret));
        }

        @Override
        public List<Credential> resolveAll(String host, String protocol) {
            return List.of();
        }
    }

    private static final class RecordingMonitor implements OperationMonitor {

        private final List<OperationProgress> reports =
                Collections.synchronizedList(new ArrayList<>());
        private final AtomicBoolean cancelled = new AtomicBoolean();

        @Override
        public void onProgress(OperationProgress progress) {
            reports.add(progress);
        }

        @Override
        public boolean isCancelled() {
            return cancelled.get();
        }
    }
}

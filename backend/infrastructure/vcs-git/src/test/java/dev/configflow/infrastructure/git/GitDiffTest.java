package dev.configflow.infrastructure.git;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.configflow.domain.vcs.model.ChangeType;
import dev.configflow.domain.vcs.model.CommitRequest;
import dev.configflow.domain.vcs.model.DiffHunk;
import dev.configflow.domain.vcs.model.FileChange;
import dev.configflow.domain.vcs.model.FileDiff;
import dev.configflow.domain.vcs.model.RepositoryHandle;
import dev.configflow.domain.vcs.model.RevisionId;
import dev.configflow.domain.vcs.model.VcsType;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.stream.IntStream;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GitDiffTest {

    private final GitRepositoryAccess access = new GitRepositoryAccess();
    private final GitDiff diffs = new GitDiff(access);
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
            // Keep the bytes on disk verbatim so the diffs are predictable on Windows.
            config.setString("core", null, "autocrlf", "false");
            config.save();
        }
    }

    // --- working tree ----------------------------------------------------

    @Test
    void diffWorking_showsUnstagedEditsAgainstTheIndex() throws Exception {
        commitFile("app.txt", "alpha\nbeta\ngamma\n");
        writeFile("app.txt", "alpha\nBETA\ngamma\n");

        FileDiff diff = diffs.diffWorking(handle, Path.of("app.txt"), false);

        assertFalse(diff.binary());
        assertEquals(ChangeType.MODIFIED, diff.type());
        assertEquals(1, diff.hunks().size());
        List<String> lines = diff.hunks().get(0).lines();
        assertTrue(lines.contains("-beta"), () -> "expected the old line in " + lines);
        assertTrue(lines.contains("+BETA"), () -> "expected the new line in " + lines);
    }

    @Test
    void diffWorking_stagedComparesTheIndexAgainstHead() throws Exception {
        commitFile("app.txt", "alpha\n");
        writeFile("app.txt", "alpha\nstaged line\n");
        workingTree.stage(handle, List.of(Path.of("app.txt")));
        // A further edit that is deliberately left out of the index.
        writeFile("app.txt", "alpha\nstaged line\nunstaged line\n");

        FileDiff staged = diffs.diffWorking(handle, Path.of("app.txt"), true);
        FileDiff unstaged = diffs.diffWorking(handle, Path.of("app.txt"), false);

        assertTrue(linesOf(staged).contains("+staged line"));
        assertFalse(linesOf(staged).contains("+unstaged line"),
                "the index must not contain the later edit");
        assertTrue(linesOf(unstaged).contains("+unstaged line"));
        assertFalse(linesOf(unstaged).contains("+staged line"));
    }

    @Test
    void diffWorking_reportsAnUnchangedFileAsAnEmptyDiff() throws Exception {
        commitFile("app.txt", "alpha\n");

        FileDiff diff = diffs.diffWorking(handle, Path.of("app.txt"), false);

        assertTrue(diff.hunks().isEmpty());
        assertFalse(diff.binary());
    }

    @Test
    void diffWorking_marksAnAddedFile() throws Exception {
        commitFile("seed.txt", "seed\n");
        writeFile("new.txt", "brand new\n");
        workingTree.stage(handle, List.of(Path.of("new.txt")));

        FileDiff diff = diffs.diffWorking(handle, Path.of("new.txt"), true);

        assertEquals(ChangeType.ADDED, diff.type());
        assertNull(diff.oldPath());
        assertTrue(linesOf(diff).contains("+brand new"));
    }

    @Test
    void diffWorking_marksADeletedFile() throws Exception {
        commitFile("gone.txt", "bye\n");
        Files.delete(repoDir.resolve("gone.txt"));

        FileDiff diff = diffs.diffWorking(handle, Path.of("gone.txt"), false);

        assertEquals(ChangeType.DELETED, diff.type());
        assertTrue(linesOf(diff).contains("-bye"));
    }

    @Test
    void diffWorking_onAnUnbornBranchTreatsStagedFilesAsAdded() throws Exception {
        // No commit yet: staging must diff against an empty tree, not blow up.
        writeFile("first.txt", "hello\n");
        workingTree.stage(handle, List.of(Path.of("first.txt")));

        FileDiff diff = diffs.diffWorking(handle, Path.of("first.txt"), true);

        assertEquals(ChangeType.ADDED, diff.type());
        assertTrue(linesOf(diff).contains("+hello"));
    }

    @Test
    void diffWorking_flagsBinaryFilesWithoutTheirContent() throws Exception {
        commitFile("seed.txt", "seed\n");
        Files.write(repoDir.resolve("image.bin"), new byte[] {0, 1, 2, 0, 3, 4, 0});
        workingTree.stage(handle, List.of(Path.of("image.bin")));

        FileDiff diff = diffs.diffWorking(handle, Path.of("image.bin"), true);

        assertTrue(diff.binary());
        assertTrue(diff.hunks().isEmpty(), "binary content must not be shipped as a diff");
    }

    @Test
    void diffWorking_isScopedToTheRequestedPath() throws Exception {
        commitFile("a.txt", "a\n");
        commitFile("b.txt", "b\n");
        writeFile("a.txt", "a changed\n");
        writeFile("b.txt", "b changed\n");

        FileDiff diff = diffs.diffWorking(handle, Path.of("a.txt"), false);

        assertEquals(Path.of("a.txt"), diff.path());
        assertFalse(linesOf(diff).stream().anyMatch(line -> line.contains("b changed")));
    }

    // --- hunk parsing ----------------------------------------------------

    @Test
    void diffWorking_splitsDistantEditsIntoSeparateHunksWithLineNumbers() throws Exception {
        // 40 lines, so edits at the top and the bottom cannot share a context window.
        String original = numberedLines(1, 40);
        commitFile("long.txt", original);
        String edited = original
                .replace("line 2\n", "line 2 edited\n")
                .replace("line 39\n", "line 39 edited\n");
        writeFile("long.txt", edited);

        FileDiff diff = diffs.diffWorking(handle, Path.of("long.txt"), false);

        assertEquals(2, diff.hunks().size());
        DiffHunk first = diff.hunks().get(0);
        DiffHunk second = diff.hunks().get(1);
        // Default context is 3 lines, so the first hunk starts at line 2 - 3 = -1 -> 1.
        assertEquals(1, first.oldStart());
        assertEquals(first.oldStart(), first.newStart());
        assertTrue(first.oldCount() > 0 && first.newCount() > 0);
        assertTrue(second.oldStart() > first.oldStart() + first.oldCount(),
                "the second hunk must start after the first one ends");
        assertTrue(first.lines().contains("+line 2 edited"));
        assertTrue(second.lines().contains("+line 39 edited"));
    }

    @Test
    void diffWorking_parsesASingleLineHunkWhoseCountIsOmitted() throws Exception {
        // Git writes "@@ -1 +1 @@" (no comma) when a hunk covers exactly one line.
        commitFile("one.txt", "only\n");
        writeFile("one.txt", "changed\n");

        DiffHunk hunk = diffs.diffWorking(handle, Path.of("one.txt"), false).hunks().get(0);

        assertEquals(1, hunk.oldStart());
        assertEquals(1, hunk.oldCount());
        assertEquals(1, hunk.newStart());
        assertEquals(1, hunk.newCount());
    }

    // --- revisions -------------------------------------------------------

    @Test
    void diffRevisions_comparesTwoCommits() throws Exception {
        RevisionId first = commitFile("app.txt", "one\n");
        RevisionId second = commitFile("app.txt", "one\ntwo\n");

        FileDiff diff = diffs.diffRevisions(handle, first, second, Path.of("app.txt"));

        assertEquals(ChangeType.MODIFIED, diff.type());
        assertTrue(linesOf(diff).contains("+two"));
    }

    @Test
    void diffRevisions_acceptsAnythingGitCanResolve() throws Exception {
        RevisionId first = commitFile("app.txt", "one\n");
        commitFile("app.txt", "one\ntwo\n");

        FileDiff diff = diffs.diffRevisions(
                handle, first, new RevisionId("HEAD"), Path.of("app.txt"));

        assertTrue(linesOf(diff).contains("+two"));
    }

    @Test
    void diffRevisions_unknownRevisionIsNotFound() throws Exception {
        RevisionId first = commitFile("app.txt", "one\n");

        assertThrows(NoSuchElementException.class, () -> diffs.diffRevisions(
                handle, first, new RevisionId("0".repeat(40)), Path.of("app.txt")));
        assertThrows(NoSuchElementException.class, () -> diffs.diffRevisions(
                handle, new RevisionId("no-such-ref"), first, Path.of("app.txt")));
    }

    @Test
    void diffRevisions_malformedRevisionIsRejectedAsBadInput() throws Exception {
        RevisionId first = commitFile("app.txt", "one\n");

        // RevisionSyntaxException is unchecked and not an IOException, so without its own
        // catch it escapes untranslated. "a b" is a plausible typo for a branch name.
        assertThrows(IllegalArgumentException.class, () -> diffs.diffRevisions(
                handle, first, new RevisionId("a b"), Path.of("app.txt")));
        assertThrows(IllegalArgumentException.class, () -> diffs.diffRevisions(
                handle, new RevisionId("HEAD^{"), first, Path.of("app.txt")));
    }

    @Test
    void contentAt_malformedRevisionIsRejectedAsBadInput() throws Exception {
        commitFile("app.txt", "one\n");

        assertThrows(IllegalArgumentException.class,
                () -> diffs.contentAt(handle, new RevisionId("a b"), Path.of("app.txt")));
    }

    // --- content ---------------------------------------------------------

    @Test
    void contentAt_readsTheFileAsOfThatRevision() throws Exception {
        RevisionId first = commitFile("app.txt", "original\n");
        commitFile("app.txt", "rewritten\n");

        assertEquals("original\n", diffs.contentAt(handle, first, Path.of("app.txt")));
        assertEquals("rewritten\n",
                diffs.contentAt(handle, new RevisionId("HEAD"), Path.of("app.txt")));
    }

    @Test
    void contentAt_returnsEmptyWhenTheFileDidNotExistYet() throws Exception {
        RevisionId first = commitFile("app.txt", "original\n");
        commitFile("later.txt", "added later\n");

        assertEquals("", diffs.contentAt(handle, first, Path.of("later.txt")));
    }

    @Test
    void contentAt_unknownRevisionIsNotFound() throws Exception {
        commitFile("app.txt", "original\n");

        assertThrows(NoSuchElementException.class,
                () -> diffs.contentAt(handle, new RevisionId("nope"), Path.of("app.txt")));
    }

    // --- commit changes --------------------------------------------------

    @Test
    void changesIn_listsWhatACommitChangedAgainstItsFirstParent() throws Exception {
        commitFile("a.txt", "a\n");
        writeFile("a.txt", "a changed\n");
        writeFile("c.txt", "c\n");
        workingTree.stage(handle, List.of(Path.of("a.txt"), Path.of("c.txt")));
        RevisionId rev = commits.commit(handle, CommitRequest.of("edit a, add c"));

        List<FileChange> changes = diffs.changesIn(handle, rev);

        assertEquals(2, changes.size(), () -> "unexpected changes: " + changes);
        assertEquals(ChangeType.MODIFIED, byPath(changes, "a.txt").type());
        assertEquals(ChangeType.ADDED, byPath(changes, "c.txt").type());
    }

    @Test
    void changesIn_onARootCommitListsEverythingAsAdded() throws Exception {
        RevisionId root = commitFile("only.txt", "x\n");

        List<FileChange> changes = diffs.changesIn(handle, root);

        assertEquals(1, changes.size());
        assertEquals(ChangeType.ADDED, changes.get(0).type());
        assertEquals(Path.of("only.txt"), changes.get(0).path());
        assertNull(changes.get(0).oldPath());
    }

    @Test
    void changesIn_marksADeletedFile() throws Exception {
        commitFile("keep.txt", "k\n");
        commitFile("gone.txt", "bye\n");
        RevisionId removed = removeAndCommit("gone.txt");

        List<FileChange> changes = diffs.changesIn(handle, removed);

        assertEquals(1, changes.size());
        FileChange gone = byPath(changes, "gone.txt");
        assertEquals(ChangeType.DELETED, gone.type());
        assertEquals(Path.of("gone.txt"), gone.path());
        // oldPath is only meaningful for renames/copies; a delete must not echo its
        // own path there or the UI shows a "gone.txt <- gone.txt" arrow.
        assertNull(gone.oldPath());
    }

    @Test
    void changesIn_detectsARenameAsOneChange() throws Exception {
        commitFile("old.txt", "same content\n");
        RevisionId renamed = renameCommit("old.txt", "new.txt", "same content\n");

        List<FileChange> changes = diffs.changesIn(handle, renamed);

        assertEquals(1, changes.size(), () -> "a rename must not split into add+delete: " + changes);
        FileChange move = changes.get(0);
        assertEquals(ChangeType.RENAMED, move.type());
        assertEquals(Path.of("new.txt"), move.path());
        assertEquals(Path.of("old.txt"), move.oldPath());
    }

    @Test
    void changesIn_unknownRevisionIsNotFound() throws Exception {
        commitFile("app.txt", "one\n");

        assertThrows(NoSuchElementException.class,
                () -> diffs.changesIn(handle, new RevisionId("0".repeat(40))));
    }

    @Test
    void changesIn_malformedRevisionIsRejectedAsBadInput() throws Exception {
        commitFile("app.txt", "one\n");

        // Same reasoning as diffRevisions: RevisionSyntaxException must land as a 400.
        assertThrows(IllegalArgumentException.class,
                () -> diffs.changesIn(handle, new RevisionId("a b")));
    }

    // --- commit file diff ------------------------------------------------

    @Test
    void diffInCommit_showsAFileChangeAgainstTheFirstParent() throws Exception {
        commitFile("app.txt", "one\n");
        RevisionId second = commitFile("app.txt", "one\ntwo\n");

        FileDiff diff = diffs.diffInCommit(handle, second, Path.of("app.txt"));

        assertEquals(ChangeType.MODIFIED, diff.type());
        assertTrue(linesOf(diff).contains("+two"));
    }

    @Test
    void diffInCommit_onARootCommitShowsTheFileAsAdded() throws Exception {
        RevisionId root = commitFile("only.txt", "hello\n");

        FileDiff diff = diffs.diffInCommit(handle, root, Path.of("only.txt"));

        assertEquals(ChangeType.ADDED, diff.type());
        assertTrue(linesOf(diff).contains("+hello"));
    }

    @Test
    void diffInCommit_returnsAnEmptyDiffForAFileTheCommitDidNotTouch() throws Exception {
        commitFile("stable.txt", "unchanged\n");
        RevisionId later = commitFile("other.txt", "new file\n");

        FileDiff diff = diffs.diffInCommit(handle, later, Path.of("stable.txt"));

        assertTrue(diff.hunks().isEmpty());
    }

    @Test
    void diffInCommit_showsARenamedFileAsARenameWithItsEdits() throws Exception {
        // changesIn reports this as RENAMED -> new.txt, so the diff for that path must
        // agree: rename detection needs both sides, which a path filter would hide.
        commitFile("old.txt", "alpha\nbeta\ngamma\ndelta\nepsilon\n");
        RevisionId renamed =
                renameCommit("old.txt", "new.txt", "alpha\nbeta\nGAMMA\ndelta\nepsilon\n");

        FileDiff diff = diffs.diffInCommit(handle, renamed, Path.of("new.txt"));

        assertEquals(ChangeType.RENAMED, diff.type());
        assertEquals(Path.of("old.txt"), diff.oldPath());
        assertTrue(linesOf(diff).contains("-gamma"), () -> "expected the old line in " + linesOf(diff));
        assertTrue(linesOf(diff).contains("+GAMMA"), () -> "expected the new line in " + linesOf(diff));
    }

    @Test
    void diffInCommit_unknownRevisionIsNotFound() throws Exception {
        commitFile("app.txt", "one\n");

        assertThrows(NoSuchElementException.class,
                () -> diffs.diffInCommit(handle, new RevisionId("0".repeat(40)), Path.of("app.txt")));
    }

    @Test
    void diffInCommit_malformedRevisionIsRejectedAsBadInput() throws Exception {
        commitFile("app.txt", "one\n");

        assertThrows(IllegalArgumentException.class,
                () -> diffs.diffInCommit(handle, new RevisionId("a b"), Path.of("app.txt")));
    }

    // --- fixture helpers -------------------------------------------------

    private static FileChange byPath(List<FileChange> changes, String path) {
        return changes.stream()
                .filter(change -> change.path().equals(Path.of(path)))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no change for " + path + " in " + changes));
    }

    private RevisionId removeAndCommit(String name) throws Exception {
        try (Git git = Git.open(repoDir.toFile())) {
            git.rm().addFilepattern(name).call();
        }
        return commits.commit(handle, CommitRequest.of("remove " + name));
    }

    private RevisionId renameCommit(String from, String to, String content) throws Exception {
        try (Git git = Git.open(repoDir.toFile())) {
            git.rm().addFilepattern(from).call();
        }
        writeFile(to, content);
        workingTree.stage(handle, List.of(Path.of(to)));
        return commits.commit(handle, CommitRequest.of("rename " + from + " to " + to));
    }

    private static List<String> linesOf(FileDiff diff) {
        return diff.hunks().stream().flatMap(hunk -> hunk.lines().stream()).toList();
    }

    private static String numberedLines(int from, int to) {
        return IntStream.rangeClosed(from, to)
                .mapToObj(i -> "line " + i + "\n")
                .reduce("", String::concat);
    }

    private void writeFile(String name, String content) throws IOException {
        Files.write(repoDir.resolve(name), content.getBytes(StandardCharsets.UTF_8));
    }

    private RevisionId commitFile(String name, String content) throws Exception {
        writeFile(name, content);
        workingTree.stage(handle, List.of(Path.of(name)));
        return commits.commit(handle, CommitRequest.of("update " + name));
    }
}

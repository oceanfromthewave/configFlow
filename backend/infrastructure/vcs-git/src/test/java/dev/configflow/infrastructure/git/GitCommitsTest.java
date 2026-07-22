package dev.configflow.infrastructure.git;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.configflow.domain.vcs.model.CommitRequest;
import dev.configflow.domain.vcs.model.HistoryQuery;
import dev.configflow.domain.vcs.model.Page;
import dev.configflow.domain.vcs.model.RefLabel;
import dev.configflow.domain.vcs.model.RepositoryHandle;
import dev.configflow.domain.vcs.model.Revision;
import dev.configflow.domain.vcs.model.RevisionId;
import dev.configflow.domain.vcs.model.VcsType;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.stream.Stream;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.revwalk.RevCommit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GitCommitsTest {

    private final GitRepositoryAccess access = new GitRepositoryAccess();
    private final GitCommits commits = new GitCommits(access);
    private final GitWorkingTree workingTree = new GitWorkingTree(access);

    @TempDir
    Path repoDir;

    private RepositoryHandle handle;

    @BeforeEach
    void initRepo() throws Exception {
        Git.init().setDirectory(repoDir.toFile()).call().close();
        handle = new RepositoryHandle(repoDir, VcsType.GIT);
        configureIdentity();
    }

    // --- commit ----------------------------------------------------------

    @Test
    void commit_recordsStagedContentAndLeavesTreeClean() throws Exception {
        writeFile("app.txt", "first version");
        workingTree.stage(handle, List.of(Path.of("app.txt")));

        RevisionId id = commits.commit(handle, CommitRequest.of("add app"));

        assertEquals(40, id.value().length(), "expected a full SHA-1");
        assertTrue(workingTree.status(handle).isClean());
        assertEquals("add app", lastCommit().getFullMessage().trim());
    }

    @Test
    void commit_onlyIncludesStagedChanges() throws Exception {
        writeFile("staged.txt", "committed content");
        workingTree.stage(handle, List.of(Path.of("staged.txt")));
        writeFile("unstaged.txt", "left behind");

        commits.commit(handle, CommitRequest.of("commit staged only"));

        // Git commits the index, so the untracked file must survive as an unstaged change.
        assertTrue(workingTree.status(handle).unstaged().stream()
                .anyMatch(c -> c.path().equals(Path.of("unstaged.txt"))));
    }

    @Test
    void amend_replacesPreviousCommitWithANewId() throws Exception {
        writeFile("app.txt", "first version");
        workingTree.stage(handle, List.of(Path.of("app.txt")));
        RevisionId original = commits.commit(handle, CommitRequest.of("typo in mesage"));

        RevisionId amended = commits.commit(handle,
                new CommitRequest("fixed message", true, List.of(), false));

        assertNotEquals(original.value(), amended.value(), "amend rewrites the commit");
        assertEquals("fixed message", lastCommit().getFullMessage().trim());
        assertEquals(1, countCommits(), "amend must not add a second commit");
    }

    // --- history ---------------------------------------------------------

    @Test
    void history_returnsNewestFirst() throws Exception {
        commitFile("a.txt", "1", "first");
        commitFile("b.txt", "2", "second");
        commitFile("c.txt", "3", "third");

        Page<Revision> page = commits.history(handle, HistoryQuery.firstPage(10));

        assertEquals(List.of("third", "second", "first"), messagesOf(page));
        assertFalse(page.hasNext());
    }

    @Test
    void history_pagesThroughCommitsWithoutDuplicates() throws Exception {
        for (int i = 1; i <= 5; i++) {
            commitFile("f" + i + ".txt", "content " + i, "commit " + i);
        }

        Page<Revision> first = commits.history(handle, HistoryQuery.firstPage(2));
        Page<Revision> second = commits.history(handle, pageAfter(first.nextCursor(), 2));
        Page<Revision> third = commits.history(handle, pageAfter(second.nextCursor(), 2));

        assertEquals(2, first.items().size());
        assertTrue(first.hasNext());
        assertEquals(2, second.items().size());
        assertEquals(1, third.items().size());
        assertFalse(third.hasNext());

        List<String> ids = Stream.of(first, second, third)
                .flatMap(p -> p.items().stream())
                .map(r -> r.id().value())
                .toList();
        assertEquals(5, new HashSet<>(ids).size(), "pages must not overlap");
    }

    @Test
    void history_filtersByMessageSubstring() throws Exception {
        commitFile("a.txt", "1", "add feature");
        commitFile("b.txt", "2", "fix bug");
        commitFile("c.txt", "3", "add tests");

        Page<Revision> page = commits.history(handle,
                new HistoryQuery(null, 10, null, null, "add", null, null, null));

        assertEquals(List.of("add tests", "add feature"), messagesOf(page));
    }

    @Test
    void history_filtersByAuthor() throws Exception {
        commitAs("a.txt", "1", "from alice", "alice");
        commitAs("b.txt", "2", "from bob", "bob");

        Page<Revision> page = commits.history(handle,
                new HistoryQuery(null, 10, null, "alice", null, null, null, null));

        assertEquals(1, page.items().size());
        assertEquals("alice", page.items().get(0).author().name());
    }

    @Test
    void history_filtersByPath() throws Exception {
        commitFile("a.txt", "1", "touch a");
        commitFile("b.txt", "2", "touch b");
        commitFile("a.txt", "1 updated", "update a");

        Page<Revision> page = commits.history(handle,
                new HistoryQuery(null, 10, null, null, null, Path.of("a.txt"), null, null));

        assertEquals(List.of("update a", "touch a"), messagesOf(page));
    }

    @Test
    void history_decoratesHeadAndBranchLabels() throws Exception {
        commitFile("a.txt", "1", "only commit");

        Revision head = commits.history(handle, HistoryQuery.firstPage(1)).items().get(0);

        assertTrue(head.labels().stream().anyMatch(l -> l.kind() == RefLabel.Kind.HEAD));
        assertTrue(head.labels().stream().anyMatch(l -> l.kind() == RefLabel.Kind.BRANCH));
    }

    @Test
    void history_recordsParentLinkage() throws Exception {
        RevisionId first = commitFile("a.txt", "1", "first");
        RevisionId second = commitFile("b.txt", "2", "second");

        Revision newest = commits.history(handle, HistoryQuery.firstPage(1)).items().get(0);

        assertEquals(second.value(), newest.id().value());
        assertEquals(List.of(first.value()),
                newest.parents().stream().map(RevisionId::value).toList());
    }

    @Test
    void history_onRepositoryWithoutCommitsIsEmpty() {
        Page<Revision> page = commits.history(handle, HistoryQuery.firstPage(10));

        assertTrue(page.items().isEmpty());
        assertFalse(page.hasNext());
    }

    @Test
    void history_treatsFilterInputAsLiteralText() throws Exception {
        commitFile("a.txt", "1", "support C++ builds");
        commitFile("b.txt", "2", "support C  builds");

        // Raw, this is a regex: `+` would quantify and never match the literal text.
        Page<Revision> page = commits.history(handle,
                new HistoryQuery(null, 10, null, null, "C++", null, null, null));

        assertEquals(List.of("support C++ builds"), messagesOf(page));
    }

    @Test
    void history_doesNotCompileFilterInputThatIsInvalidRegex() throws Exception {
        commitFile("a.txt", "1", "fix bug (again)");

        // `(again` is an unbalanced group: compiled as a regex this throws.
        Page<Revision> byMessage = commits.history(handle,
                new HistoryQuery(null, 10, null, null, "(again", null, null, null));
        Page<Revision> byAuthor = commits.history(handle,
                new HistoryQuery(null, 10, null, "[unclosed", null, null, null, null));

        assertEquals(1, byMessage.items().size());
        assertTrue(byAuthor.items().isEmpty());
    }

    @Test
    void history_ignoresBlankFilters() throws Exception {
        commitFile("a.txt", "1", "only commit");

        // JGit rejects an empty pattern outright, so blanks must not reach it.
        Page<Revision> page = commits.history(handle,
                new HistoryQuery(null, 10, null, "  ", "", null, null, null));

        assertEquals(1, page.items().size());
    }

    // --- show ------------------------------------------------------------

    @Test
    void show_returnsTheRequestedRevision() throws Exception {
        RevisionId first = commitFile("a.txt", "1", "first");
        commitFile("b.txt", "2", "second");

        Revision revision = commits.show(handle, first);

        assertEquals(first.value(), revision.id().value());
        assertEquals("first", revision.message().trim());
    }

    @Test
    void show_acceptsAnythingGitCanResolve() throws Exception {
        RevisionId id = commitFile("a.txt", "1", "only commit");

        assertEquals(id.value(), commits.show(handle, new RevisionId("HEAD")).id().value());
        assertEquals(id.value(),
                commits.show(handle, new RevisionId(id.value().substring(0, 7))).id().value());
    }

    @Test
    void show_unknownRevisionIsNotFound() throws Exception {
        commitFile("a.txt", "1", "only commit");

        assertThrows(NoSuchElementException.class,
                () -> commits.show(handle, new RevisionId("0".repeat(40))));
    }

    // --- fixture helpers -------------------------------------------------

    private static HistoryQuery pageAfter(String cursor, int limit) {
        return new HistoryQuery(cursor, limit, null, null, null, null, null, null);
    }

    private static List<String> messagesOf(Page<Revision> page) {
        return page.items().stream().map(r -> r.message().trim()).toList();
    }

    private void writeFile(String name, String content) throws IOException {
        Files.writeString(repoDir.resolve(name), content);
    }

    private RevisionId commitFile(String name, String content, String message) throws Exception {
        writeFile(name, content);
        workingTree.stage(handle, List.of(Path.of(name)));
        return commits.commit(handle, CommitRequest.of(message));
    }

    /** Commits directly through JGit so the test can control the author identity. */
    private void commitAs(String name, String content, String message, String author)
            throws Exception {
        writeFile(name, content);
        try (Git git = Git.open(repoDir.toFile())) {
            git.add().addFilepattern(name).call();
            git.commit()
                    .setMessage(message)
                    .setAuthor(author, author + "@configflow.dev")
                    .setCommitter(author, author + "@configflow.dev")
                    .call();
        }
    }

    /**
     * Production code lets JGit read the identity from git config, so the test writes one
     * into the temp repository instead of depending on the developer's machine.
     */
    private void configureIdentity() throws Exception {
        try (Git git = Git.open(repoDir.toFile())) {
            var config = git.getRepository().getConfig();
            config.setString("user", null, "name", "Test");
            config.setString("user", null, "email", "test@configflow.dev");
            config.save();
        }
    }

    private RevCommit lastCommit() throws IOException, GitAPIException {
        try (Git git = Git.open(repoDir.toFile())) {
            return git.log().setMaxCount(1).call().iterator().next();
        }
    }

    private int countCommits() throws IOException, GitAPIException {
        try (Git git = Git.open(repoDir.toFile())) {
            int count = 0;
            for (RevCommit ignored : git.log().call()) {
                count++;
            }
            return count;
        }
    }
}

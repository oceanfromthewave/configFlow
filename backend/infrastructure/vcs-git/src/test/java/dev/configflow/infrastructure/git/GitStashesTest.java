package dev.configflow.infrastructure.git;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.configflow.domain.vcs.exception.VcsPreconditionException;
import dev.configflow.domain.vcs.model.CommitRequest;
import dev.configflow.domain.vcs.model.RepositoryHandle;
import dev.configflow.domain.vcs.model.StashEntry;
import dev.configflow.domain.vcs.model.VcsType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.NoSuchElementException;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GitStashesTest {

    private final GitRepositoryAccess access = new GitRepositoryAccess();
    private final GitStashes stashes = new GitStashes(access);
    private final GitWorkingTree workingTree = new GitWorkingTree(access);
    private final GitCommits commits = new GitCommits(access);

    @TempDir
    Path repoDir;

    private RepositoryHandle handle;

    @BeforeEach
    void initRepo() throws Exception {
        Git.init().setDirectory(repoDir.toFile()).call().close();
        handle = new RepositoryHandle(repoDir, VcsType.GIT);

        Files.writeString(repoDir.resolve("file.txt"), "initial content\n");
        workingTree.stage(handle, List.of(Path.of("file.txt")));
        commits.commit(handle, CommitRequest.of("Initial commit"));
    }

    @Test
    void saveAndListStash() throws Exception {
        Files.writeString(repoDir.resolve("file.txt"), "modified content\n");

        stashes.save(handle, "work in progress", false);

        List<StashEntry> list = stashes.list(handle);
        assertEquals(1, list.size());
        assertFalse(list.get(0).message().isEmpty());
        assertEquals("initial content", Files.readString(repoDir.resolve("file.txt")).strip());
    }

    @Test
    void saveWithNoChangesThrowsException() {
        assertThrows(VcsPreconditionException.class, () -> stashes.save(handle, "empty", false));
    }

    @Test
    void saveWithIncludeUntrackedStashesNewFiles() throws Exception {
        Files.writeString(repoDir.resolve("untracked.txt"), "new file\n");

        stashes.save(handle, "wip", true);

        assertEquals(1, stashes.list(handle).size());
        assertFalse(Files.exists(repoDir.resolve("untracked.txt")));
    }

    @Test
    void applyWithConflictingLocalChangesThrows() throws Exception {
        Files.writeString(repoDir.resolve("file.txt"), "modified content\n");
        stashes.save(handle, "wip", false);

        Files.writeString(repoDir.resolve("file.txt"), "conflicting local edit\n");

        assertThrows(VcsPreconditionException.class, () -> stashes.apply(handle, 0));
    }

    @Test
    void applyStash() throws Exception {
        Files.writeString(repoDir.resolve("file.txt"), "modified content\n");
        stashes.save(handle, "wip", false);

        stashes.apply(handle, 0);

        assertEquals("modified content", Files.readString(repoDir.resolve("file.txt")).strip());
        assertEquals(1, stashes.list(handle).size());
    }

    @Test
    void popStash() throws Exception {
        Files.writeString(repoDir.resolve("file.txt"), "modified content\n");
        stashes.save(handle, "wip", false);

        stashes.pop(handle, 0);

        assertEquals("modified content", Files.readString(repoDir.resolve("file.txt")).strip());
        assertEquals(0, stashes.list(handle).size());
    }

    @Test
    void dropStash() throws Exception {
        Files.writeString(repoDir.resolve("file.txt"), "modified content\n");
        stashes.save(handle, "wip", false);
        assertEquals(1, stashes.list(handle).size());

        stashes.drop(handle, 0);

        assertEquals(0, stashes.list(handle).size());
    }

    @Test
    void applyPopDropOnOutOfRangeIndexThrowNoSuchElement() throws Exception {
        Files.writeString(repoDir.resolve("file.txt"), "modified content\n");
        stashes.save(handle, "wip", false);

        assertThrows(NoSuchElementException.class, () -> stashes.apply(handle, 1));
        assertThrows(NoSuchElementException.class, () -> stashes.pop(handle, 1));
        assertThrows(NoSuchElementException.class, () -> stashes.drop(handle, 1));
    }
}

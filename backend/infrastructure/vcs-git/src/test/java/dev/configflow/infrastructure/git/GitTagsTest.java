package dev.configflow.infrastructure.git;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import org.eclipse.jgit.lib.Ref;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GitTagsTest {

    private final GitRepositoryAccess access = new GitRepositoryAccess();
    private final GitTags tags = new GitTags(access);
    private final GitWorkingTree workingTree = new GitWorkingTree(access);
    private final GitCommits commits = new GitCommits(access);

    @TempDir
    Path repoDir;

    private RepositoryHandle handle;
    private RevisionId firstCommit;

    @BeforeEach
    void initRepo() throws Exception {
        Git.init().setDirectory(repoDir.toFile()).call().close();
        handle = new RepositoryHandle(repoDir, VcsType.GIT);

        Files.writeString(repoDir.resolve("file.txt"), "initial content\n");
        workingTree.stage(handle, List.of(Path.of("file.txt")));
        firstCommit = commits.commit(handle, CommitRequest.of("Initial commit"));
    }

    private List<Ref> jgitTags() throws Exception {
        try (Git git = access.open(handle)) {
            return git.tagList().call();
        }
    }

    @Test
    void createLightweightTagOnHead() throws Exception {
        tags.create(handle, "v1.0", null, null);

        List<Ref> refs = jgitTags();
        assertEquals(1, refs.size());
        assertEquals("refs/tags/v1.0", refs.get(0).getName());
    }

    @Test
    void createAnnotatedTagWithMessage() throws Exception {
        tags.create(handle, "v1.0", null, "release notes");

        try (Git git = access.open(handle)) {
            Ref ref = git.getRepository().findRef("refs/tags/v1.0");
            // An annotated tag's ref points at a tag object, not the commit directly, so
            // peeling is required to reach the commit.
            assertTrue(git.getRepository().getRefDatabase().peel(ref).getPeeledObjectId() != null);
        }
    }

    @Test
    void createTagOnExplicitRevision() throws Exception {
        Files.writeString(repoDir.resolve("file.txt"), "second\n");
        workingTree.stage(handle, List.of(Path.of("file.txt")));
        commits.commit(handle, CommitRequest.of("Second commit"));

        tags.create(handle, "v1.0", firstCommit, null);

        try (Git git = access.open(handle)) {
            Ref ref = git.getRepository().findRef("refs/tags/v1.0");
            assertEquals(firstCommit.value(), ref.getObjectId().getName());
        }
    }

    @Test
    void createDuplicateTagThrowsPrecondition() {
        tags.create(handle, "v1.0", null, null);

        assertThrows(VcsPreconditionException.class, () -> tags.create(handle, "v1.0", null, null));
    }

    @Test
    void createWithInvalidNameThrowsIllegalArgument() {
        assertThrows(IllegalArgumentException.class, () -> tags.create(handle, "..bad", null, null));
    }

    @Test
    void createWithUnresolvableRevisionThrowsNoSuchElement() {
        assertThrows(NoSuchElementException.class,
                () -> tags.create(handle, "v1.0", new RevisionId("deadbeef"), null));
    }

    @Test
    void deleteTag() throws Exception {
        tags.create(handle, "v1.0", null, null);

        tags.delete(handle, "v1.0");

        assertEquals(0, jgitTags().size());
    }

    @Test
    void deleteMissingTagThrowsNoSuchElement() {
        assertThrows(NoSuchElementException.class, () -> tags.delete(handle, "nope"));
    }
}

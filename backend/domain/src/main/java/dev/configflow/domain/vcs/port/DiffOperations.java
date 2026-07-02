package dev.configflow.domain.vcs.port;

import dev.configflow.domain.vcs.model.FileDiff;
import dev.configflow.domain.vcs.model.RepositoryHandle;
import dev.configflow.domain.vcs.model.RevisionId;
import java.nio.file.Path;

/**
 * Port for producing structured diffs and historical file content.
 */
public interface DiffOperations {

    /** Diff of a working-tree file against HEAD (or the index when {@code staged}). */
    FileDiff diffWorking(RepositoryHandle repo, Path path, boolean staged);

    /** Diff of a file between two revisions. */
    FileDiff diffRevisions(RepositoryHandle repo, RevisionId from, RevisionId to, Path path);

    /** Full content of a file as of the given revision. */
    String contentAt(RepositoryHandle repo, RevisionId revision, Path path);
}

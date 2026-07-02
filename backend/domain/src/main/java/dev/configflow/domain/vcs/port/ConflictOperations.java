package dev.configflow.domain.vcs.port;

import dev.configflow.domain.vcs.model.ConflictedFile;
import dev.configflow.domain.vcs.model.RepositoryHandle;
import dev.configflow.domain.vcs.model.ThreeWayContent;
import java.nio.file.Path;
import java.util.List;

/**
 * Port for listing and resolving merge/update conflicts.
 */
public interface ConflictOperations {

    /** Files currently in conflicted state. */
    List<ConflictedFile> listConflicts(RepositoryHandle repo);

    /** Base/mine/theirs content of one conflicted file for the merge editor. */
    ThreeWayContent threeWayContent(RepositoryHandle repo, Path path);

    /**
     * Marks a conflict as resolved. {@code manualContent} is the merged file content
     * when {@code resolution == MANUAL}, otherwise {@code null}.
     */
    void resolve(
            RepositoryHandle repo,
            Path path,
            ConflictedFile.Resolution resolution,
            String manualContent);
}

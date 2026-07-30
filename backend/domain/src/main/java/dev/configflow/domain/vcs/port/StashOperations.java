package dev.configflow.domain.vcs.port;

import dev.configflow.domain.vcs.model.RepositoryHandle;
import dev.configflow.domain.vcs.model.StashEntry;
import java.util.List;

/**
 * Optional port for stashing local changes (Git-only; requires the {@code STASH}
 * capability).
 */
public interface StashOperations {

    /** All stash entries, newest first. */
    List<StashEntry> list(RepositoryHandle repo);

    /** Saves the current working-tree changes as a new stash. */
    void save(RepositoryHandle repo, String message, boolean includeUntracked);

    /** Applies stash {@code index} keeping the entry. */
    void apply(RepositoryHandle repo, int index);

    /** Applies stash {@code index} and drops it on success. */
    void pop(RepositoryHandle repo, int index);

    /** Deletes stash {@code index}. */
    void drop(RepositoryHandle repo, int index);
}

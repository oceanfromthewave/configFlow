package dev.configflow.domain.vcs.port;

import dev.configflow.domain.vcs.model.CommitRequest;
import dev.configflow.domain.vcs.model.HistoryQuery;
import dev.configflow.domain.vcs.model.Page;
import dev.configflow.domain.vcs.model.RepositoryHandle;
import dev.configflow.domain.vcs.model.Revision;
import dev.configflow.domain.vcs.model.RevisionId;

/**
 * Port for creating commits and reading history.
 */
public interface CommitOperations {

    /** Creates a commit and returns the new revision id. */
    RevisionId commit(RepositoryHandle repo, CommitRequest request);

    /** Reads history with cursor paging (mandatory: history may be huge). */
    Page<Revision> history(RepositoryHandle repo, HistoryQuery query);

    /** Loads one revision's metadata. */
    Revision show(RepositoryHandle repo, RevisionId id);
}

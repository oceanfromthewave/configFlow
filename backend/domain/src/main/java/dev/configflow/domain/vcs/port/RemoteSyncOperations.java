package dev.configflow.domain.vcs.port;

import dev.configflow.domain.operation.OperationHandle;
import dev.configflow.domain.vcs.model.FetchRequest;
import dev.configflow.domain.vcs.model.PullRequest;
import dev.configflow.domain.vcs.model.PushRequest;
import dev.configflow.domain.vcs.model.RepositoryHandle;

/**
 * Port for synchronizing with remotes. Git implements fetch/pull/push; SVN implements
 * update/cleanup (its fetch+integrate equivalent).
 */
public interface RemoteSyncOperations {

    /** Downloads new revisions without touching the working tree (Git). */
    OperationHandle fetch(RepositoryHandle repo, FetchRequest request);

    /** Fetch + integrate into the current branch (Git). */
    OperationHandle pull(RepositoryHandle repo, PullRequest request);

    /** Uploads local commits (Git). */
    OperationHandle push(RepositoryHandle repo, PushRequest request);

    /** SVN update, optionally to a specific revision ({@code null} = HEAD). */
    OperationHandle update(RepositoryHandle repo, Long revision);

    /** SVN cleanup of a stale working copy. */
    OperationHandle cleanup(RepositoryHandle repo);
}

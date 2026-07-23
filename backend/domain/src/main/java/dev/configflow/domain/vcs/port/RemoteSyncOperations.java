package dev.configflow.domain.vcs.port;

import dev.configflow.domain.vcs.model.FetchRequest;
import dev.configflow.domain.vcs.model.PullRequest;
import dev.configflow.domain.vcs.model.PushRequest;
import dev.configflow.domain.vcs.model.RepositoryHandle;

/**
 * Port for synchronizing with remotes. Git implements fetch/pull/push; SVN implements
 * update/cleanup (its fetch+integrate equivalent).
 *
 * <p>Plain synchronous calls that report through the monitor they are given. They talk
 * to the network and can run for minutes, so callers put them on the operation queue —
 * but that is the application's decision: making a provider hand back an
 * {@code OperationHandle} would mean every provider had to know about queueing.</p>
 */
public interface RemoteSyncOperations {

    /** Downloads new revisions without touching the working tree (Git). */
    void fetch(RepositoryHandle repo, FetchRequest request, OperationMonitor monitor);

    /** Fetch + integrate into the current branch (Git). */
    void pull(RepositoryHandle repo, PullRequest request, OperationMonitor monitor);

    /** Uploads local commits (Git). */
    void push(RepositoryHandle repo, PushRequest request, OperationMonitor monitor);

    /** SVN update, optionally to a specific revision ({@code null} = HEAD). */
    void update(RepositoryHandle repo, Long revision, OperationMonitor monitor);

    /** SVN cleanup of a stale working copy. */
    void cleanup(RepositoryHandle repo);
}

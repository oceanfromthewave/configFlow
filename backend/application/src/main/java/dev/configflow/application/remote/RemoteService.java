package dev.configflow.application.remote;

import dev.configflow.application.operation.OperationContext;
import dev.configflow.application.operation.OperationQueue;
import dev.configflow.application.vcs.VcsAccess;
import dev.configflow.domain.operation.ConsoleLevel;
import dev.configflow.domain.operation.Operation;
import dev.configflow.domain.operation.OperationEvents;
import dev.configflow.domain.operation.OperationType;
import dev.configflow.domain.repository.RepositoryId;
import dev.configflow.domain.vcs.model.FetchRequest;
import dev.configflow.domain.vcs.model.PullRequest;
import dev.configflow.domain.vcs.model.PushRequest;
import dev.configflow.domain.vcs.port.OperationMonitor;
import dev.configflow.domain.vcs.port.RemoteSyncOperations;
import java.util.Objects;

/**
 * Use case: talking to remotes, executed through the operation queue.
 *
 * <p>These are the slowest things the app does — minutes on a large repository — so the
 * queue is not an implementation detail here: it is what keeps the UI responsive and
 * what makes a transfer cancellable.</p>
 */
public final class RemoteService {

    private final VcsAccess access;
    private final OperationQueue queue;
    private final OperationEvents events;

    public RemoteService(VcsAccess access, OperationQueue queue, OperationEvents events) {
        this.access = Objects.requireNonNull(access, "access");
        this.queue = Objects.requireNonNull(queue, "queue");
        this.events = Objects.requireNonNull(events, "events");
    }

    /** Downloads new revisions without touching the working tree. */
    public Operation fetch(RepositoryId id, String remote, boolean prune) {
        FetchRequest request = new FetchRequest(trimmed(remote), prune);
        VcsAccess.Opened<RemoteSyncOperations> opened =
                access.open(id, RemoteSyncOperations.class);

        return queue.submit(id, OperationType.FETCH, context -> {
            context.log("fetch " + describe(request.remote()) + (prune ? " --prune" : ""),
                    ConsoleLevel.CMD);
            opened.operations().fetch(opened.handle(), request, monitorFor(context));
            // Only remote-tracking refs moved; the working tree is untouched.
            events.refsChanged(id);
        });
    }

    /** Fetch plus integrate into the current branch. */
    public Operation pull(RepositoryId id, String remote, PullRequest.Strategy strategy) {
        PullRequest request = new PullRequest(
                trimmed(remote), strategy == null ? PullRequest.Strategy.MERGE : strategy);
        VcsAccess.Opened<RemoteSyncOperations> opened =
                access.open(id, RemoteSyncOperations.class);

        return queue.submit(id, OperationType.PULL, context -> {
            context.log("pull " + describe(request.remote())
                    + (request.strategy() == PullRequest.Strategy.REBASE ? " --rebase" : ""),
                    ConsoleLevel.CMD);
            opened.operations().pull(opened.handle(), request, monitorFor(context));
            events.refsChanged(id);
            events.workingTreeChanged(id);
        });
    }

    /** Uploads local commits. */
    public Operation push(
            RepositoryId id, String remote, boolean forceWithLease, boolean pushTags) {
        PushRequest request = new PushRequest(trimmed(remote), forceWithLease, pushTags);
        VcsAccess.Opened<RemoteSyncOperations> opened =
                access.open(id, RemoteSyncOperations.class);

        return queue.submit(id, OperationType.PUSH, context -> {
            context.log("push " + describe(request.remote())
                    + (forceWithLease ? " --force-with-lease" : "")
                    + (pushTags ? " --tags" : ""), ConsoleLevel.CMD);
            opened.operations().push(opened.handle(), request, monitorFor(context));
            // Nothing local changed, but the remote-tracking refs caught up.
            events.refsChanged(id);
        });
    }

    /**
     * Lets the engine report progress and ask whether to stop, both of which the running
     * operation already knows how to answer.
     */
    private static OperationMonitor monitorFor(OperationContext context) {
        return new OperationMonitor() {
            @Override
            public void onProgress(dev.configflow.domain.operation.OperationProgress progress) {
                context.progress(progress);
            }

            @Override
            public boolean isCancelled() {
                return context.isCancelled();
            }
        };
    }

    private static String describe(String remote) {
        return remote == null ? "origin" : remote;
    }

    private static String trimmed(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}

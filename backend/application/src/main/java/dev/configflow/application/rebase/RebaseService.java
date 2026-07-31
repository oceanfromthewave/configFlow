package dev.configflow.application.rebase;

import dev.configflow.application.operation.OperationQueue;
import dev.configflow.application.vcs.VcsAccess;
import dev.configflow.domain.operation.ConsoleLevel;
import dev.configflow.domain.operation.Operation;
import dev.configflow.domain.operation.OperationEvents;
import dev.configflow.domain.operation.OperationType;
import dev.configflow.domain.repository.RepositoryId;
import dev.configflow.domain.vcs.model.RepositoryHandle;
import dev.configflow.domain.vcs.port.RebaseOperations;

import java.util.Objects;
import java.util.function.BiConsumer;

public final class RebaseService
{
	private final VcsAccess access;
	private final OperationQueue queue;
	private final OperationEvents events;

	public RebaseService(VcsAccess access, OperationQueue queue, OperationEvents events)
	{
		this.access = Objects.requireNonNull(access, "access");
		this.queue = Objects.requireNonNull(queue, "queue");
		this.events = Objects.requireNonNull(events, "events");
	}

	/** Rebases the current branch onto {@code upstream}. */
	public Operation start(RepositoryId id, String upstream)
	{
		if(upstream == null || upstream.isBlank())
		{
			throw new IllegalArgumentException("'upstream' must not be blank");
		}
		String ref = upstream.trim();
		return submit(id, "rebase " + ref, (operations, handle) -> operations.start(handle, ref));
	}

	/** Continues a rebase that stopped on conflicts, once they are resolved and staged. */
	public Operation continueRebase(RepositoryId id)
	{
		return submit(id, "rebase --continue", RebaseOperations::continueRebase);
	}

	/** Aborts the in-progress rebase and restores the pre-rebase state. */
	public Operation abort(RepositoryId id)
	{
		return submit(id, "rebase --abort", RebaseOperations::abort);
	}

	/** Drops the commit the rebase stopped on and carries on with the rest. */
	public Operation skip(RepositoryId id)
	{
		return submit(id, "rebase --skip", RebaseOperations::skip);
	}

	/**
	 * The four rebase steps differ only in the console line and the port call, so
	 * they share one submission path.
	 */
	private Operation submit(RepositoryId id, String command, BiConsumer<RebaseOperations, RepositoryHandle> action)
	{
		VcsAccess.Opened<RebaseOperations> opened = access.open(id, RebaseOperations.class);

		return queue.submit(id, OperationType.REBASE, context -> {
			context.log(command, ConsoleLevel.CMD);
			context.throwIfCancelled();
			action.accept(opened.operations(), opened.handle());
			// A rebase rewrites HEAD and replays commits over the working tree, so
			// nothing cached about this repository survives — abort included, since
			// it restores the pre-rebase state.
			events.refsChanged(id);
			events.workingTreeChanged(id);
		});
	}
}

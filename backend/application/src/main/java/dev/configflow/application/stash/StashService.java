package dev.configflow.application.stash;

import dev.configflow.application.operation.OperationQueue;
import dev.configflow.application.vcs.VcsAccess;
import dev.configflow.domain.operation.ConsoleLevel;
import dev.configflow.domain.operation.Operation;
import dev.configflow.domain.operation.OperationEvents;
import dev.configflow.domain.operation.OperationType;
import dev.configflow.domain.repository.RepositoryId;
import dev.configflow.domain.vcs.model.StashEntry;
import dev.configflow.domain.vcs.port.StashOperations;

import java.util.List;
import java.util.Objects;

public class StashService
{
	private final VcsAccess access;
	private final OperationQueue queue;
	private final OperationEvents events;

	public StashService(VcsAccess access, OperationQueue queue, OperationEvents events)
	{
		this.access = Objects.requireNonNull(access, "access");
		this.queue = Objects.requireNonNull(queue, "queue");
		this.events = Objects.requireNonNull(events, "events");
	}

	public List<StashEntry> list(RepositoryId id)
	{
		VcsAccess.Opened<StashOperations> opened = access.open(id, StashOperations.class);
		return opened.operations().list(opened.handle());
	}

	public Operation save(RepositoryId id, String message, boolean includeUntracked)
	{
		VcsAccess.Opened<StashOperations> opened = access.open(id, StashOperations.class);
		return queue.submit(id, OperationType.STASH, context -> {
			context.log("stash save " + (message != null ? message : ""), ConsoleLevel.CMD);
			context.throwIfCancelled();
			opened.operations().save(opened.handle(), message, includeUntracked);
			events.workingTreeChanged(id);
		});
	}

	public Operation apply(RepositoryId id, int index)
	{
		requireValidIndex(index);
		VcsAccess.Opened<StashOperations> opened = access.open(id, StashOperations.class);
		return queue.submit(id, OperationType.STASH, context -> {
			context.log("stash apply stash@{" + index + "}", ConsoleLevel.CMD);
			context.throwIfCancelled();
			opened.operations().apply(opened.handle(), index);
			events.workingTreeChanged(id);
		});
	}

	public Operation pop(RepositoryId id, int index)
	{
		requireValidIndex(index);
		VcsAccess.Opened<StashOperations> opened = access.open(id, StashOperations.class);
		return queue.submit(id, OperationType.STASH, context -> {
			context.log("stash pop stash@{" + index + "}", ConsoleLevel.CMD);
			context.throwIfCancelled();
			opened.operations().pop(opened.handle(), index);
			events.workingTreeChanged(id);
		});
	}

	public Operation drop(RepositoryId id, int index)
	{
		requireValidIndex(index);
		VcsAccess.Opened<StashOperations> opened = access.open(id, StashOperations.class);
		return queue.submit(id, OperationType.STASH, context -> {
			context.log("stash drop stash@{" + index + "}", ConsoleLevel.CMD);
			context.throwIfCancelled();
			opened.operations().drop(opened.handle(), index);
			events.workingTreeChanged(id);
		});
	}

	private static void requireValidIndex(int index)
	{
		if(index < 0)
		{
			throw new IllegalArgumentException("stash index must be non-negative: " + index);
		}
	}
}
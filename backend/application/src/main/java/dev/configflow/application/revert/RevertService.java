package dev.configflow.application.revert;

import dev.configflow.application.operation.OperationQueue;
import dev.configflow.application.vcs.VcsAccess;
import dev.configflow.domain.operation.ConsoleLevel;
import dev.configflow.domain.operation.Operation;
import dev.configflow.domain.operation.OperationEvents;
import dev.configflow.domain.operation.OperationType;
import dev.configflow.domain.repository.RepositoryId;
import dev.configflow.domain.vcs.model.RevisionId;
import dev.configflow.domain.vcs.port.RevertOperations;

import java.util.List;
import java.util.Objects;

public final class RevertService
{
	private final VcsAccess access;
	private final OperationQueue queue;
	private final OperationEvents events;

	public RevertService(VcsAccess access, OperationQueue queue, OperationEvents events)
	{
		this.access = Objects.requireNonNull(access, "access");
		this.queue = Objects.requireNonNull(queue, "queue");
		this.events = Objects.requireNonNull(events, "events");
	}

	/** Records the inverse of the given commits, in request order, on the current branch. */
	public Operation revert(RepositoryId id, List<String> revisions)
	{
		if(revisions == null || revisions.isEmpty())
		{
			throw new IllegalArgumentException("'revisions' must not be empty");
		}
		// Validated before the port is resolved: otherwise a blank revision sent to a
		// provider without the capability would be reported as the wrong error.
		List<String> refs = revisions.stream().map(RevertService::requireRevision).toList();
		List<RevisionId> reverted = refs.stream().map(RevisionId::new).toList();
		String command = "revert " + String.join(" ", refs);

		VcsAccess.Opened<RevertOperations> opened = access.open(id, RevertOperations.class);

		return queue.submit(id, OperationType.REVERT, context -> {
			context.log(command, ConsoleLevel.CMD);
			context.throwIfCancelled();
			try
			{
				opened.operations().revert(opened.handle(), reverted);
			}
			finally
			{
				// A revert of several commits stops at the first one that conflicts, so the
				// earlier inverse commits are already on the branch even when this throws —
				// the panels have to show them either way.
				events.refsChanged(id);
				events.workingTreeChanged(id);
			}
		});
	}

	private static String requireRevision(String revision)
	{
		if(revision == null || revision.isBlank())
		{
			throw new IllegalArgumentException("'revisions' must not contain a blank entry");
		}
		return revision.trim();
	}
}

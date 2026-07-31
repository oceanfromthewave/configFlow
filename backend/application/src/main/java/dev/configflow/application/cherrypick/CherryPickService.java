package dev.configflow.application.cherrypick;

import dev.configflow.application.operation.OperationQueue;
import dev.configflow.application.vcs.VcsAccess;
import dev.configflow.domain.operation.ConsoleLevel;
import dev.configflow.domain.operation.Operation;
import dev.configflow.domain.operation.OperationEvents;
import dev.configflow.domain.operation.OperationType;
import dev.configflow.domain.repository.RepositoryId;
import dev.configflow.domain.vcs.model.RevisionId;
import dev.configflow.domain.vcs.port.CherryPickOperations;

import java.util.List;
import java.util.Objects;

public final class CherryPickService
{
	private final VcsAccess access;
	private final OperationQueue queue;
	private final OperationEvents events;

	public CherryPickService(VcsAccess access, OperationQueue queue, OperationEvents events)
	{
		this.access = Objects.requireNonNull(access, "access");
		this.queue = Objects.requireNonNull(queue, "queue");
		this.events = Objects.requireNonNull(events, "events");
	}

	/** Replays the given commits, in request order, onto the current branch. */
	public Operation cherryPick(RepositoryId id, List<String> revisions)
	{
		if(revisions == null || revisions.isEmpty())
		{
			throw new IllegalArgumentException("'revisions' must not be empty");
		}
		// Validated before the port is resolved: otherwise a blank revision sent to a
		// provider without the capability would be reported as the wrong error.
		List<String> refs = revisions.stream().map(CherryPickService::requireRevision).toList();
		List<RevisionId> picked = refs.stream().map(RevisionId::new).toList();
		String command = "cherry-pick " + String.join(" ", refs);

		VcsAccess.Opened<CherryPickOperations> opened = access.open(id, CherryPickOperations.class);

		return queue.submit(id, OperationType.CHERRY_PICK, context -> {
			context.log(command, ConsoleLevel.CMD);
			context.throwIfCancelled();
			opened.operations().cherryPick(opened.handle(), picked);
			// Each picked commit is a new commit on the current branch, and the working
			// tree lands on it — or conflicted, which the panels have to show either way.
			events.refsChanged(id);
			events.workingTreeChanged(id);
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

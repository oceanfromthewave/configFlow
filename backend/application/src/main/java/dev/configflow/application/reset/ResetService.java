package dev.configflow.application.reset;

import dev.configflow.application.operation.OperationQueue;
import dev.configflow.application.vcs.VcsAccess;
import dev.configflow.domain.operation.ConsoleLevel;
import dev.configflow.domain.operation.Operation;
import dev.configflow.domain.operation.OperationEvents;
import dev.configflow.domain.operation.OperationType;
import dev.configflow.domain.repository.RepositoryId;
import dev.configflow.domain.vcs.model.ResetMode;
import dev.configflow.domain.vcs.model.RevisionId;
import dev.configflow.domain.vcs.port.ResetOperations;

import java.util.Locale;
import java.util.Objects;

public final class ResetService
{
	private final VcsAccess access;
	private final OperationQueue queue;
	private final OperationEvents events;

	public ResetService(VcsAccess access, OperationQueue queue, OperationEvents events)
	{
		this.access = Objects.requireNonNull(access, "access");
		this.queue = Objects.requireNonNull(queue, "queue");
		this.events = Objects.requireNonNull(events, "events");
	}

	/** Moves the current branch to {@code target}; {@code mode} decides whether the index and working tree follow. */
	public Operation reset(RepositoryId id, String target, String mode)
	{
		if(target == null || target.isBlank())
		{
			throw new IllegalArgumentException("'target' must not be blank");
		}
		String ref = target.trim();
		ResetMode resetMode = parseMode(mode);
		String command = "reset --" + resetMode.name().toLowerCase(Locale.ROOT) + " " + ref;

		VcsAccess.Opened<ResetOperations> opened = access.open(id, ResetOperations.class);

		return queue.submit(id, OperationType.RESET, context -> {
			context.log(command, ConsoleLevel.CMD);
			context.throwIfCancelled();
			try
			{
				opened.operations().reset(opened.handle(), new RevisionId(ref), resetMode);
			}
			finally
			{
				// A hard reset rewrites the working tree and every mode moves the branch, so
				// both panels are stale afterwards — including when the reset failed partway.
				try
				{
					events.refsChanged(id);
					events.workingTreeChanged(id);
				}
				catch(RuntimeException ignored)
				{
					// A finally block that throws replaces the reset's own exception, so the
					// user would see "notification failed" instead of why the reset failed.
					// The refresh is best-effort; the outcome is not.
				}
			}
		});
	}

	private static ResetMode parseMode(String mode)
	{
		if(mode == null || mode.isBlank())
		{
			return ResetMode.MIXED;
		}
		try
		{
			return ResetMode.valueOf(mode.trim().toUpperCase(Locale.ROOT));
		}
		catch(IllegalArgumentException e)
		{
			throw new IllegalArgumentException("'mode' must be one of soft, mixed, hard", e);
		}
	}
}

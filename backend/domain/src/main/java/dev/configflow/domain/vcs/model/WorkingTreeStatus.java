package dev.configflow.domain.vcs.model;

import java.util.List;

/**
 * Snapshot of the working tree state.
 *
 * @param staged
 * 		staged changes; always empty for providers without the {@code STAGING} capability (e.g. SVN)
 * @param unstaged
 * 		unstaged (working copy) changes
 * @param conflicted
 * 		files currently in conflict
 */
public record WorkingTreeStatus(List<FileChange> staged, List<FileChange> unstaged, List<ConflictedFile> conflicted, boolean rebasing)
{

	public WorkingTreeStatus
	{
		staged = List.copyOf(staged == null ? List.of() : staged);
		unstaged = List.copyOf(unstaged == null ? List.of() : unstaged);
		conflicted = List.copyOf(conflicted == null ? List.of() : conflicted);
	}

	/** An entirely clean working tree. */
	public static WorkingTreeStatus clean()
	{
		return new WorkingTreeStatus(List.of(), List.of(), List.of(), false);
	}

	/** True when nothing is modified, staged or conflicted. */
	public boolean isClean()
	{
		return staged.isEmpty() && unstaged.isEmpty() && conflicted.isEmpty();
	}
}
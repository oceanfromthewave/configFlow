package dev.configflow.domain.vcs.model;

public enum ResetMode
{
	/** Moves HEAD only; index and working tree stay untouched. */
	SOFT,
	/** Moves HEAD and resets the index; the working tree stays untouched. */
	MIXED,
	/** Moves HEAD and resets both index and working tree, discarding local changes. */
	HARD
}
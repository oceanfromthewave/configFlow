package dev.configflow.domain.vcs.exception;

/**
 * The request was well formed, but the working copy is in a state that blocks it —
 * uncommitted changes in the way of a checkout, an unmerged branch being deleted without
 * force, and so on.
 *
 * <p>Distinct from a plain failure because the user can act on it: commit, stash, or
 * repeat the command with force. Distinct from bad input because nothing about the
 * request needs changing.</p>
 */
public class VcsPreconditionException extends VcsException {

    public VcsPreconditionException(String message) {
        super(message);
    }

    public VcsPreconditionException(String message, Throwable cause) {
        super(message, cause);
    }
}

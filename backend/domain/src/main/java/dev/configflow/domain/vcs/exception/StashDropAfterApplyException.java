package dev.configflow.domain.vcs.exception;

/**
 * A stash was successfully applied to the working tree, but the follow-up
 * drop of that stash entry failed. The working tree changed and callers must
 * still treat it as such, even though the operation itself failed.
 */
public class StashDropAfterApplyException extends VcsException {

    public StashDropAfterApplyException(String message, Throwable cause) {
        super(message, cause);
    }
}

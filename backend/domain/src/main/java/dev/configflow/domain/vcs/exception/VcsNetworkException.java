package dev.configflow.domain.vcs.exception;

/**
 * A remote operation failed for network reasons (unknown host, connection refused,
 * timeout, interrupted transfer). Retrying later may succeed; credentials are not
 * the problem.
 */
public class VcsNetworkException extends VcsException {

    public VcsNetworkException(String message, Throwable cause) {
        super(message, cause);
    }
}

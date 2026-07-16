package dev.configflow.domain.vcs.exception;

/**
 * Base class of all VCS domain exceptions.
 *
 * <p>Infrastructure adapters translate engine-specific failures (JGit, SVNKit)
 * into this hierarchy so the application and API layers never depend on
 * engine exception types.</p>
 */
public class VcsException extends RuntimeException {

    public VcsException(String message) {
        super(message);
    }

    public VcsException(String message, Throwable cause) {
        super(message, cause);
    }
}

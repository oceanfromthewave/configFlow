package dev.configflow.domain.vcs.exception;

import java.util.Objects;

/**
 * A remote operation failed because valid credentials are required (or the stored
 * ones were rejected). The API layer maps this to {@code VCS_AUTH_REQUIRED} so the
 * frontend can prompt for credentials for the given host/protocol.
 */
public class VcsAuthenticationRequiredException extends VcsException {

    private final String host;
    private final String protocol;

    public VcsAuthenticationRequiredException(String host, String protocol, Throwable cause) {
        super("Authentication required for " + protocol + "://" + host, cause);
        this.host = Objects.requireNonNull(host, "host must not be null");
        this.protocol = Objects.requireNonNull(protocol, "protocol must not be null");
    }

    /** Remote host that rejected the request, e.g. {@code github.com}. */
    public String host() {
        return host;
    }

    /** Protocol used to reach the host: {@code https}, {@code ssh} or {@code svn}. */
    public String protocol() {
        return protocol;
    }
}

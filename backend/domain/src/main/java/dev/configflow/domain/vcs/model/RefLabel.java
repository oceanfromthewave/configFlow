package dev.configflow.domain.vcs.model;

import java.util.Objects;

/**
 * A ref decoration attached to a revision (branch head, tag, HEAD marker).
 *
 * @param kind what kind of ref this is
 * @param name short ref name, e.g. {@code main}, {@code origin/main}, {@code v1.2.0}
 */
public record RefLabel(Kind kind, String name) {

    /** Ref categories rendered differently in the commit graph. */
    public enum Kind {
        BRANCH,
        REMOTE_BRANCH,
        TAG,
        HEAD
    }

    public RefLabel {
        Objects.requireNonNull(kind, "kind must not be null");
        Objects.requireNonNull(name, "name must not be null");
    }
}

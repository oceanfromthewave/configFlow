package dev.configflow.domain.vcs.model;

import java.util.Objects;

/**
 * VCS-native identifier of a revision.
 *
 * <p>Git: SHA-1/SHA-256 hex string. SVN: revision number rendered as {@code "r1234"}.</p>
 */
public record RevisionId(String value) {

    public RevisionId {
        Objects.requireNonNull(value, "value must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException("value must not be blank");
        }
    }
}

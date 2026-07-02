package dev.configflow.domain.vcs.model;

import java.util.Objects;

/**
 * Author of a revision.
 *
 * @param name  display name (SVN username when no full name is available)
 * @param email email address, may be {@code null} (SVN often has none)
 */
public record Author(String name, String email) {

    public Author {
        Objects.requireNonNull(name, "name must not be null");
    }
}

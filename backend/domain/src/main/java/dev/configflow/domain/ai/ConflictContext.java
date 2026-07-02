package dev.configflow.domain.ai;

import java.util.Objects;

/**
 * Three-way conflict payload sent to an AI provider for a merge proposal.
 *
 * @param path   conflicted file path (repository-relative, as a string to stay serializable)
 * @param base   common ancestor content, may be {@code null}
 * @param mine   local content
 * @param theirs incoming content
 */
public record ConflictContext(String path, String base, String mine, String theirs) {

    public ConflictContext {
        Objects.requireNonNull(path, "path must not be null");
        Objects.requireNonNull(mine, "mine must not be null");
        Objects.requireNonNull(theirs, "theirs must not be null");
    }
}

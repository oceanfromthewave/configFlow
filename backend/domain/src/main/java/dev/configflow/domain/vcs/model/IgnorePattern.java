package dev.configflow.domain.vcs.model;

import java.util.Objects;

/**
 * A single ignore rule (Git: {@code .gitignore} line, SVN: {@code svn:ignore} entry).
 *
 * @param pattern glob pattern relative to the repository root
 */
public record IgnorePattern(String pattern) {

    public IgnorePattern {
        Objects.requireNonNull(pattern, "pattern must not be null");
        if (pattern.isBlank()) {
            throw new IllegalArgumentException("pattern must not be blank");
        }
    }
}

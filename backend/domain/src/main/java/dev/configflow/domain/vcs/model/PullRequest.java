package dev.configflow.domain.vcs.model;

import java.util.Objects;

/**
 * Request to pull (fetch + integrate) from a remote.
 *
 * @param remote   remote name, {@code null} for the default remote
 * @param strategy how to integrate the fetched commits
 */
public record PullRequest(String remote, Strategy strategy) {

    /** Integration strategy after fetch. */
    public enum Strategy {
        MERGE,
        REBASE
    }

    public PullRequest {
        Objects.requireNonNull(strategy, "strategy must not be null");
    }
}

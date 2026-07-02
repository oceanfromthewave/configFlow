package dev.configflow.domain.vcs.model;

import java.util.Objects;

/**
 * Request to merge another ref into the current branch.
 *
 * @param source          ref to merge from
 * @param fastForwardOnly fail instead of creating a merge commit
 * @param squash          squash the merged commits into the working tree
 */
public record MergeRequest(String source, boolean fastForwardOnly, boolean squash) {

    public MergeRequest {
        Objects.requireNonNull(source, "source must not be null");
    }
}

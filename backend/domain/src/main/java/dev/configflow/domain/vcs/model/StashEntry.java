package dev.configflow.domain.vcs.model;

import java.time.Instant;
import java.util.Objects;

/**
 * One entry in the Git stash list.
 *
 * @param index     stash position ({@code stash@{index}})
 * @param message   stash message
 * @param createdAt creation time
 */
public record StashEntry(int index, String message, Instant createdAt) {

    public StashEntry {
        Objects.requireNonNull(message, "message must not be null");
    }
}

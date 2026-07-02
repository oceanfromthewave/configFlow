package dev.configflow.domain.vcs.model;

import java.util.List;

/**
 * Cursor-paged result used by potentially unbounded queries (history, graph).
 *
 * @param items      page contents
 * @param nextCursor opaque cursor for the next page, {@code null} when exhausted
 * @param <T>        element type
 */
public record Page<T>(List<T> items, String nextCursor) {

    public Page {
        items = List.copyOf(items == null ? List.of() : items);
    }

    /** True when a further page can be fetched. */
    public boolean hasNext() {
        return nextCursor != null;
    }
}

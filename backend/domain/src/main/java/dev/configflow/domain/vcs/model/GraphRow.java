package dev.configflow.domain.vcs.model;

import java.util.List;
import java.util.Objects;

/**
 * One row of the commit graph, ready for rendering.
 *
 * <p>Lane assignment (which vertical lane a revision and its connecting edges occupy)
 * is computed in the application layer so every VCS shares the same algorithm.</p>
 *
 * @param revision the revision drawn on this row
 * @param lane     vertical lane index of the revision node (0-based)
 * @param edges    edges entering/leaving this row
 */
public record GraphRow(Revision revision, int lane, List<Edge> edges) {

    /** Visual classification of a graph edge. */
    public enum EdgeType {
        NORMAL,
        MERGE,
        BRANCH_OUT
    }

    /**
     * A single edge segment on this row.
     *
     * @param fromLane lane the edge comes from (row above)
     * @param toLane   lane the edge goes to (this row / row below)
     * @param type     visual classification
     */
    public record Edge(int fromLane, int toLane, EdgeType type) {

        public Edge {
            Objects.requireNonNull(type, "type must not be null");
        }
    }

    public GraphRow {
        Objects.requireNonNull(revision, "revision must not be null");
        if (lane < 0) {
            throw new IllegalArgumentException("lane must be >= 0");
        }
        edges = List.copyOf(edges == null ? List.of() : edges);
    }
}

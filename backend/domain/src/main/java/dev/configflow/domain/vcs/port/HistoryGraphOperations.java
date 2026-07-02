package dev.configflow.domain.vcs.port;

import dev.configflow.domain.vcs.model.GraphRow;
import dev.configflow.domain.vcs.model.HistoryQuery;
import dev.configflow.domain.vcs.model.Page;
import dev.configflow.domain.vcs.model.RepositoryHandle;

/**
 * Port for the commit graph: history rows with lane assignment already computed.
 */
public interface HistoryGraphOperations {

    /** Graph rows with lane/edge layout, cursor-paged like plain history. */
    Page<GraphRow> graph(RepositoryHandle repo, HistoryQuery query);
}

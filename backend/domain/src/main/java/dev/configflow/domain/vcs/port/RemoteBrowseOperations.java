package dev.configflow.domain.vcs.port;

import dev.configflow.domain.vcs.model.RemoteEntry;
import dev.configflow.domain.vcs.model.RevisionId;
import java.util.List;

/**
 * Optional port for browsing a remote repository tree without a working copy
 * (SVN repository browser; requires the {@code REMOTE_BROWSE} capability).
 */
public interface RemoteBrowseOperations {

    /** Lists the children of {@code url} as of {@code revision} ({@code null} = HEAD). */
    List<RemoteEntry> browse(String url, RevisionId revision);
}

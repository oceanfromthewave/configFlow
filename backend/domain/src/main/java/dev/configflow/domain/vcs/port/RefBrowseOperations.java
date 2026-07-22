package dev.configflow.domain.vcs.port;

import dev.configflow.domain.vcs.model.RefLabel;
import dev.configflow.domain.vcs.model.RepositoryHandle;
import dev.configflow.domain.vcs.model.Revision;

import java.util.List;

public interface RefBrowseOperations
{
	List<RefLabel> listRefs(RepositoryHandle repo);

	List<Revision> compare(RepositoryHandle repo, String base, String target);
}

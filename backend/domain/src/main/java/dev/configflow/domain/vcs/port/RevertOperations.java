package dev.configflow.domain.vcs.port;

import dev.configflow.domain.vcs.model.RepositoryHandle;
import dev.configflow.domain.vcs.model.RevisionId;

import java.util.List;

public interface RevertOperations
{
	void revert(RepositoryHandle repo, List<RevisionId> revisions);
}

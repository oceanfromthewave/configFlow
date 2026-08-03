package dev.configflow.domain.vcs.port;

import dev.configflow.domain.vcs.model.RepositoryHandle;
import dev.configflow.domain.vcs.model.ResetMode;
import dev.configflow.domain.vcs.model.RevisionId;

public interface ResetOperations
{
	void reset(RepositoryHandle repo, RevisionId target, ResetMode mode);
}

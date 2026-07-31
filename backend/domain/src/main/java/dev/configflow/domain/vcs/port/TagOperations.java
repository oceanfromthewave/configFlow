package dev.configflow.domain.vcs.port;

import dev.configflow.domain.vcs.model.RepositoryHandle;
import dev.configflow.domain.vcs.model.RevisionId;

/**
 * Optional port for tag management (requires the {@code TAG} capability).
 */
public interface TagOperations
{
	void create(RepositoryHandle repo, String name, RevisionId target, String message);

	void delete(RepositoryHandle repo, String name);
}

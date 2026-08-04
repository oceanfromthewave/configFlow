package dev.configflow.domain.operation;

import dev.configflow.domain.repository.RepositoryId;

import java.nio.file.Path;

public interface WorkingTreeWatch
{
	void watch(RepositoryId id, Path localPath);

	void unwatch(RepositoryId id);

	static WorkingTreeWatch noop()
	{
		return new WorkingTreeWatch()
		{
			@Override
			public void watch(RepositoryId id, Path localPath)
			{

			}

			@Override
			public void unwatch(RepositoryId id)
			{

			}
		};
	}
}

package dev.configflow.config;

import dev.configflow.application.credential.StoredRemoteCredentials;
import dev.configflow.application.repository.RepositoryService;
import dev.configflow.application.vcs.DefaultVcsProviderRegistry;
import dev.configflow.domain.credential.CredentialRefStore;
import dev.configflow.domain.credential.CredentialStore;
import dev.configflow.domain.credential.RemoteCredentialResolver;
import dev.configflow.domain.repository.RepositoryStore;
import dev.configflow.domain.vcs.port.VcsProvider;
import dev.configflow.domain.vcs.port.VcsProviderRegistry;
import dev.configflow.infrastructure.git.GitVcsProvider;

import java.time.Clock;
import java.util.List;

import dev.configflow.infrastructure.svn.SvnVcsProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Assembles the VCS engine: the installed providers, the registry that indexes them, and the repository use case. This is the composition root where the domain
 * ports meet their infrastructure implementations (only bootstrap is allowed to know both sides).
 */
@Configuration
public class VcsConfig
{

	/**
	 * Turns a remote's host/protocol into the stored credential to present, spanning the reference table and the OS keychain. The Git provider consults this on
	 * fetch/pull/push/clone; nothing is presented when no credential is stored.
	 */
	@Bean
	public RemoteCredentialResolver remoteCredentialResolver(CredentialRefStore credentialRefStore, CredentialStore credentialStore)
	{
		return new StoredRemoteCredentials(credentialRefStore, credentialStore);
	}

	/** The JGit-backed Git provider, contributed to the provider list. */
	@Bean
	public GitVcsProvider gitVcsProvider(RemoteCredentialResolver remoteCredentialResolver)
	{
		return new GitVcsProvider(remoteCredentialResolver);
	}

	/**
	 * The SVNKit-backed Subversion provider. No credential resolver yet: this slice checks out anonymously, and authenticated remotes arrive with the update
	 * slice.
	 */
	@Bean
	public SvnVcsProvider svnVcsProvider()
	{
		return new SvnVcsProvider();
	}

	/**
	 * Registry over every {@link VcsProvider} bean. Spring injects the full list, so a new VCS becomes available simply by declaring its provider as a bean.
	 */
	@Bean
	public VcsProviderRegistry vcsProviderRegistry(List<VcsProvider> providers)
	{
		return new DefaultVcsProviderRegistry(providers);
	}

	/** Wall-clock source; injected so time stays a controllable dependency. */
	@Bean
	public Clock clock()
	{
		return Clock.systemUTC();
	}

	@Bean
	public RepositoryService repositoryService(RepositoryStore repositoryStore, VcsProviderRegistry providers, Clock clock)
	{
		return new RepositoryService(repositoryStore, providers, clock);
	}
}

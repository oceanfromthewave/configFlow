package dev.configflow.config;

import dev.configflow.application.repository.RepositoryService;
import dev.configflow.application.vcs.DefaultVcsProviderRegistry;
import dev.configflow.domain.repository.RepositoryStore;
import dev.configflow.domain.vcs.port.VcsProvider;
import dev.configflow.domain.vcs.port.VcsProviderRegistry;
import dev.configflow.infrastructure.git.GitVcsProvider;
import java.time.Clock;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Assembles the VCS engine: the installed providers, the registry that indexes them,
 * and the repository use case. This is the composition root where the domain ports meet
 * their infrastructure implementations (only bootstrap is allowed to know both sides).
 */
@Configuration
public class VcsConfig {

    /** The JGit-backed Git provider, contributed to the provider list. */
    @Bean
    public GitVcsProvider gitVcsProvider() {
        return new GitVcsProvider();
    }

    /**
     * Registry over every {@link VcsProvider} bean. Spring injects the full list, so a new
     * VCS becomes available simply by declaring its provider as a bean.
     */
    @Bean
    public VcsProviderRegistry vcsProviderRegistry(List<VcsProvider> providers) {
        return new DefaultVcsProviderRegistry(providers);
    }

    /** Wall-clock source; injected so time stays a controllable dependency. */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    public RepositoryService repositoryService(
            RepositoryStore repositoryStore, VcsProviderRegistry providers, Clock clock) {
        return new RepositoryService(repositoryStore, providers, clock);
    }
}

package dev.configflow.application.vcs;

import dev.configflow.domain.vcs.model.VcsType;
import dev.configflow.domain.vcs.port.VcsProvider;
import dev.configflow.domain.vcs.port.VcsProviderRegistry;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * In-memory {@link VcsProviderRegistry}: providers are registered once during assembly
 * and then looked up by type or by auto-detecting which one owns a working copy.
 *
 * <p>This is the plugin seam. Supporting a new VCS means adding a {@link VcsProvider}
 * implementation and registering it here; no other layer changes.</p>
 */
public final class DefaultVcsProviderRegistry implements VcsProviderRegistry {

	private final ConcurrentMap<VcsType, VcsProvider> providersByType = new
			ConcurrentHashMap<>();

	/** Registers every provider up front (convenient for dependency-injection wiring). */
	public DefaultVcsProviderRegistry(List<VcsProvider> providers) {
		providers.forEach(this::register);
	}

	@Override
	public void register(VcsProvider provider) {
		providersByType.put(provider.type(), provider);
	}

	@Override
	public Optional<VcsProvider> byType(VcsType type) {
		return Optional.ofNullable(providersByType.get(type));
	}

	@Override
	public Optional<VcsProvider> detect(Path localPath) {
		return providersByType.values().stream()
				.filter(provider -> provider.detect(localPath))
				.findFirst();
	}

	@Override
	public List<VcsProvider> all() {
		return new ArrayList<>(providersByType.values());
	}
}

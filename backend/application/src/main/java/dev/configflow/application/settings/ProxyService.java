package dev.configflow.application.settings;

import dev.configflow.domain.settings.ProxySettings;
import dev.configflow.domain.settings.SettingsStore;

import java.net.ProxySelector;
import java.util.Objects;
import java.util.Optional;

/**
 * Use case: read, store and apply the HTTP proxy.
 *
 * <p>Applying means installing a {@link ProxySelector} as the JVM default, so a change takes effect on the next connection without a restart.</p>
 */
public final class ProxyService
{
	static final String URL_KEY = "proxy.url";
	static final String BYPASS_KEY = "proxy.bypass";

	/**
	 * The selector in place before we touched anything — the JDK's own, which honours {@code http.proxyHost} and the OS proxy. Clearing our proxy restores it
	 * rather than leaving the JVM with no selector at all.
	 */
	private static final ProxySelector SYSTEM_DEFAULT = ProxySelector.getDefault();

	private final SettingsStore settings;

	public ProxyService(SettingsStore settings)
	{
		this.settings = Objects.requireNonNull(settings, "settings must not be null");
	}

	/** The stored proxy, empty when connections should go direct. */
	public Optional<ProxySettings> current()
	{
		return settings.get(URL_KEY)
				.filter(url -> !url.isBlank())
				.map(url -> ProxySettings.parse(url, settings.get(BYPASS_KEY).orElse("")));
	}

	/** Stores a proxy and applies it immediately. */
	public ProxySettings update(String url, String bypass)
	{
		ProxySettings proxy = ProxySettings.parse(url, bypass);
		settings.put(URL_KEY, proxy.url());
		settings.put(BYPASS_KEY, String.join(",", proxy.bypass()));
		apply(Optional.of(proxy));
		return proxy;
	}

	/** Removes the proxy; connections go direct again (no-op when none is stored). */
	public void clear()
	{
		settings.remove(URL_KEY);
		settings.remove(BYPASS_KEY);
		apply(Optional.empty());
	}

	/** Installs whatever is stored. Called once at startup. */
	public void applyStored()
	{
		apply(current());
	}

	private static void apply(Optional<ProxySettings> proxy)
	{
		ProxySelector.setDefault(proxy.<ProxySelector>map(SettingsProxySelector::new).orElse(SYSTEM_DEFAULT));
	}
}

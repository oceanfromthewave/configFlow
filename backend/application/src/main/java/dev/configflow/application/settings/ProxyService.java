package dev.configflow.application.settings;

import dev.configflow.domain.settings.ProxySettings;
import dev.configflow.domain.settings.SettingsStore;

import java.lang.System.Logger.Level;
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
	private static final System.Logger log = System.getLogger(ProxyService.class.getName());

	static final String URL_KEY = "proxy.url";
	static final String BYPASS_KEY = "proxy.bypass";

	/**
	 * The selector in place before we touched anything — the JDK's own, which honours {@code http.proxyHost} and the OS proxy. Clearing our proxy restores it
	 * rather than leaving the JVM with no selector at all.
	 */
	private static final ProxySelector SYSTEM_DEFAULT = ProxySelector.getDefault();

	/** Guards the settings/selector pair so a concurrent read never sees a half-written update. */
	private final Object lock = new Object();

	private final SettingsStore settings;

	public ProxyService(SettingsStore settings)
	{
		this.settings = Objects.requireNonNull(settings, "settings must not be null");
	}

	/**
	 * The stored proxy, empty when connections should go direct.
	 *
	 * <p>A value that no longer parses (hand-edited database, format change) falls back to
	 * "no proxy" rather than propagating — a bad row here must not fail every settings read,
	 * or worse, fail application startup via {@link #applyStored()}.</p>
	 */
	public Optional<ProxySettings> current()
	{
		synchronized(lock)
		{
			return currentLocked();
		}
	}

	private Optional<ProxySettings> currentLocked()
	{
		return settings.get(URL_KEY)
				.filter(url -> !url.isBlank())
				.flatMap(url ->
				{
					try
					{
						return Optional.of(ProxySettings.parse(url, settings.get(BYPASS_KEY).orElse("")));
					}
					catch(IllegalArgumentException e)
					{
						// Not e.getMessage(): ProxySettings.parse() echoes the raw URL back into its
						// exception message, and a corrupted row could still carry user:pass@.
						log.log(Level.WARNING, "Stored proxy setting is invalid; falling back to no proxy.");
						return Optional.empty();
					}
				});
	}

	/** Stores a proxy and applies it immediately. */
	public ProxySettings update(String url, String bypass)
	{
		ProxySettings proxy = ProxySettings.parse(url, bypass);
		synchronized(lock)
		{
			settings.put(URL_KEY, proxy.url());
			settings.put(BYPASS_KEY, String.join(",", proxy.bypass()));
			apply(Optional.of(proxy));
		}
		return proxy;
	}

	/** Removes the proxy; connections go direct again (no-op when none is stored). */
	public void clear()
	{
		synchronized(lock)
		{
			settings.remove(URL_KEY);
			settings.remove(BYPASS_KEY);
			apply(Optional.empty());
		}
	}

	/** Installs whatever is stored. Called once at startup. */
	public void applyStored()
	{
		synchronized(lock)
		{
			apply(currentLocked());
		}
	}

	private static void apply(Optional<ProxySettings> proxy)
	{
		ProxySelector.setDefault(proxy.<ProxySelector>map(SettingsProxySelector::new).orElse(SYSTEM_DEFAULT));
	}
}

package dev.configflow.application.settings;

import dev.configflow.domain.settings.ProxySettings;

import java.io.IOException;
import java.net.*;
import java.util.List;
import java.util.Objects;

/**
 * Routes outbound connections through the configured proxy.
 *
 * <p>Installed as the JVM default because that is the only hook JGit's HTTP transport offers — {@code TransportHttp} exposes no proxy setter and resolves one
 * through {@code HttpSupport.proxyFor(ProxySelector.getDefault(), url)}.</p>
 */
final class SettingsProxySelector extends ProxySelector
{
	private final ProxySettings settings;
	private final Proxy proxy;

	SettingsProxySelector(ProxySettings settings)
	{
		this.settings = Objects.requireNonNull(settings, "settings must not be null");
		// Unresolved on purpose: the proxy host is resolved by the connection attempt, so a
		// DNS blip at configuration time is not a permanent failure.
		this.proxy = new Proxy(settings.scheme().equals("socks") ? Proxy.Type.SOCKS : Proxy.Type.HTTP,
				InetSocketAddress.createUnresolved(settings.host(), settings.port()));
	}

	@Override
	public List<Proxy> select(URI uri)
	{
		Objects.requireNonNull(uri, "uri must not be null");
		return settings.bypasses(uri.getHost()) ? List.of(Proxy.NO_PROXY) : List.of(proxy);
	}

	@Override
	public void connectFailed(URI uri, SocketAddress address, IOException failure)
	{
		// One configured proxy, no alternatives to fall back to. The caller already sees the
		// connection error; recording a second copy here would add nothing.
	}
}

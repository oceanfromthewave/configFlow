package dev.configflow.domain.settings;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * HTTP proxy configuration, stored as the {@code proxy.url} / {@code proxy.bypass} settings and applied to every outbound VCS connection.
 */
public record ProxySettings(String scheme, String host, int port, List<String> bypass)
{

	public ProxySettings
	{
		Objects.requireNonNull(scheme, "scheme must not be null");
		Objects.requireNonNull(host, "host must not be null");
		bypass = List.copyOf(Objects.requireNonNull(bypass, "bypass must not be null"));
	}

	/**
	 * Parses the stored form. {@code url} is {@code http://host:port} (or {@code socks://}); {@code bypass} is a comma-separated host list, each entry either
	 * an exact host or a {@code *.domain} wildcard.
	 *
	 * @throws IllegalArgumentException
	 * 		when the URL is not a usable proxy address
	 */
	public static ProxySettings parse(String url, String bypass)
	{
		java.net.URI uri;
		try
		{
			uri = new java.net.URI(url.trim());
		}
		catch(Exception e)
		{
			throw new IllegalArgumentException("Proxy URL is malformed: " + url, e);
		}
		String scheme = uri.getScheme() == null ? "http" : uri.getScheme().toLowerCase(Locale.ROOT);
		if(!scheme.equals("http") && !scheme.equals("https") && !scheme.equals("socks"))
		{
			throw new IllegalArgumentException("Proxy scheme must be http, https or socks: " + scheme);
		}
		if(uri.getHost() == null || uri.getHost().isBlank())
		{
			throw new IllegalArgumentException("Proxy URL must contain a host: " + url);
		}
		// No port in the URL is a configuration mistake worth reporting, not a default to
		// guess — an 80 that should have been 3128 fails as a silent connection timeout.
		if(uri.getPort() <= 0)
		{
			throw new IllegalArgumentException("Proxy URL must contain a port: " + url);
		}
		return new ProxySettings(scheme, uri.getHost(), uri.getPort(), splitBypass(bypass));
	}

	private static List<String> splitBypass(String bypass)
	{
		if(bypass == null || bypass.isBlank())
		{
			return List.of();
		}
		return java.util.Arrays.stream(bypass.split(",")).map(String::trim).filter(entry -> !entry.isEmpty()).toList();
	}

	/** Whether {@code host} should be reached directly rather than through the proxy. */
	public boolean bypasses(String host)
	{
		if(host == null || host.isBlank())
		{
			return false;
		}
		String target = host.toLowerCase(Locale.ROOT);
		return bypass.stream().anyMatch(entry -> matches(entry.toLowerCase(Locale.ROOT), target));
	}

	private static boolean matches(String pattern, String host)
	{
		if(pattern.startsWith("*."))
		{
			String domain = pattern.substring(2);
			return host.equals(domain) || host.endsWith("." + domain);
		}
		return host.equals(pattern);
	}

	/** The stored {@code proxy.url} form, round-tripping through {@link #parse}. */
	public String url()
	{
		return scheme + "://" + host + ":" + port;
	}
}

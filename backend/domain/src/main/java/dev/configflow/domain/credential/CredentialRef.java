package dev.configflow.domain.credential;

import java.time.Instant;
import java.util.Objects;

/**
 * What the database keeps about a credential: everything except the secret.
 *
 * <p>The secret lives in the OS store and is reachable only through {@link #storeKey()}.
 * Nothing here is worth protecting on its own, which is the point — a stolen database file yields no passwords.</p>
 *
 * @param username
 * 		account name; {@code ""} for token-only auth, never {@code null}
 * @param storeKey
 * 		opaque handle into the OS credential store
 */
public record CredentialRef(CredentialId id, String host, String protocol, String username, String storeKey, Instant createdAt)
{

	public CredentialRef
	{
		Objects.requireNonNull(id, "id must not be null");
		Objects.requireNonNull(storeKey, "storeKey must not be null");
		Objects.requireNonNull(createdAt, "createdAt must not be null");

		// Hosts and protocols are case-insensitive, so GitHub.com and github.com are one
		// host. Left alone they would be two rows, and the second would shadow the first
		// for whichever spelling the URL happened to use.
		host = requireText(host, "host").toLowerCase();
		protocol = requireText(protocol, "protocol").toLowerCase();

		// Empty rather than null, because SQLite counts NULLs as distinct in a UNIQUE
		// index: (github.com, https, NULL) can be inserted any number of times, and
		// token-only auth is exactly the case that has no username.
		username = username == null ? "" : username.trim();
	}

	/** A credential just placed in the OS store. */
	public static CredentialRef issue(String host, String protocol, String username, String storeKey, Instant now)
	{
		return new CredentialRef(CredentialId.newId(), host, protocol, username, storeKey, now);
	}

	/** True when this credential is the one to use for {@code protocol://host}. */
	public boolean matches(String host, String protocol)
	{
		return this.host.equalsIgnoreCase(host) && this.protocol.equalsIgnoreCase(protocol);
	}

	private static String requireText(String value, String field)
	{
		if(value == null || value.isBlank())
		{
			throw new IllegalArgumentException("'" + field + "' must not be blank");
		}
		return value.trim();
	}
}

package dev.configflow.application.credential;

import dev.configflow.domain.credential.Credential;
import dev.configflow.domain.credential.CredentialRefStore;
import dev.configflow.domain.credential.CredentialStore;
import dev.configflow.domain.credential.RemoteCredentialResolver;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Resolves a remote's credential across the two stores: the reference table names which secret to use for a host/protocol, and the OS keychain hands back the
 * secret itself.
 *
 * <p>The whole point of the split is preserved here — the reference alone is worthless, so
 * a resolution that finds a row but no secret behind it (a keychain wiped out of band) yields empty rather than a half-built credential.</p>
 */
public final class StoredRemoteCredentials implements RemoteCredentialResolver
{

	private final CredentialRefStore refs;
	private final CredentialStore secrets;

	public StoredRemoteCredentials(CredentialRefStore refs, CredentialStore secrets)
	{
		this.refs = Objects.requireNonNull(refs, "refs");
		this.secrets = Objects.requireNonNull(secrets, "secrets");
	}

	@Override
	public Optional<Credential> resolve(String host, String protocol)
	{
		return refs.findFor(host, protocol).flatMap(ref -> secrets.find(ref.storeKey()));
	}

	@Override
	public List<Credential> resolveAll(String host, String protocol)
	{
		return refs.findAll().stream().filter(ref -> ref.matches(host, protocol)).map(ref -> secrets.find(ref.storeKey())).flatMap(Optional::stream)
				.toList();
	}
}
package dev.configflow.domain.credential;

import java.util.List;
import java.util.Optional;

public interface RemoteCredentialResolver
{
	Optional<Credential> resolve(String host, String protocol);

	/**
	 * Every stored credential for one host and protocol.
	 *
	 * <p>SSH needs this because a key is offered before the server says which one it
	 * wants: the client presents its identities and the server picks. Scoped to the host
	 * as well as the protocol — offering a key meant for one server to a different one
	 * leaks which identities the user holds, and can exhaust a server's authentication
	 * attempt limit before the right key is ever tried.</p>
	 */
	List<Credential> resolveAll(String host, String protocol);
}

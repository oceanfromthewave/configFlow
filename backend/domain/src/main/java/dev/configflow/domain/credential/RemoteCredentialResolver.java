package dev.configflow.domain.credential;

import java.util.Optional;

public interface RemoteCredentialResolver
{
	Optional<Credential> resolve(String host, String protocol);
}

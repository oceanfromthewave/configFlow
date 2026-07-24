package dev.configflow.domain.credential;

import java.util.List;
import java.util.Optional;

public interface CredentialRefStore
{
	void save(CredentialRef ref);

	Optional<CredentialRef> findById(CredentialId id);

	Optional<CredentialRef> findByTarget(String host, String protocol, String username);

	List<CredentialRef> findAll();

	void delete(CredentialId id);
}

package dev.configflow.domain.credential;

/**
 * Port for creating SSH key material.
 *
 * <p>Generation lives behind a port because it is cryptography, not domain logic: the
 * domain only cares that something can hand it a private key to store and a public key
 * to show the user.</p>
 */
public interface SshKeyFactory
{

	/**
	 * Generates a fresh Ed25519 key pair.
	 *
	 * @param comment
	 * 		trailing comment on the public key line, e.g. {@code user@host}
	 */
	SshKeyPair generate(String comment);
}

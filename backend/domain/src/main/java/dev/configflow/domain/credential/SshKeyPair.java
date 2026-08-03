package dev.configflow.domain.credential;

import java.util.Objects;

public record SshKeyPair(char[] privateKeyPem, String publicKey)
{
	public SshKeyPair
	{
		Objects.requireNonNull(privateKeyPem, "privateKeyPem must not be null");
		Objects.requireNonNull(publicKey, "publicKey must not be null");
	}

	@Override
	public String toString()
	{
		return "SshKeyPair[publicKey=" + publicKey + ", privateKeyPem=***]";
	}
}

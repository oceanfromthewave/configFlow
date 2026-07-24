package dev.configflow.domain.credential;

import java.util.UUID;

public record CredentialId(UUID value)
{
	/** Creates a new random identity. */
	public static CredentialId newId()
	{
		return new CredentialId(UUID.randomUUID());
	}

	/** Parses the canonical UUID string form. */
	public static CredentialId of(String value)
	{
		return new CredentialId(UUID.fromString(value));
	}

	/** Canonical string form used in APIs and persistence. */
	public String asString()
	{
		return value.toString();
	}
}

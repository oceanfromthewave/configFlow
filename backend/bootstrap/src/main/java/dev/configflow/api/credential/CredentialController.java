package dev.configflow.api.credential;

import dev.configflow.application.credential.CredentialService;
import dev.configflow.domain.credential.CredentialId;
import dev.configflow.domain.credential.CredentialRef;

import java.time.Instant;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/**
 * Credential API: register a secret, list what is registered and remove one.
 *
 * <p>The secret only ever travels inbound. It arrives on {@code POST}, is handed
 * straight to the OS store by {@link CredentialService}, and is never part of any response — not the store key either, which is an opaque internal handle. A
 * client that lists credentials sees who and where, never the password.</p>
 */
@RestController
@RequestMapping("/api/v1/credentials")
public class CredentialController
{

	/**
	 * POST body. {@code username} is optional (token-only auth has none); {@code secret} is required.
	 */
	public record SaveRequest(String host, String protocol, String username, String secret)
	{
	}

	/** POST body for generating an SSH key. Only {@code host} is required. */
	public record CreateSshKeyRequest(String host, String username, String comment)
	{
	}

	/**
	 * A credential as the client sees it: everything identifying, nothing secret.
	 *
	 * <p>{@code publicKey} is the exception that proves the rule — it is the half of an
	 * SSH key meant to be handed out, and the user needs it to register the key with their server. {@code null} for password and token credentials.</p>
	 */
	public record CredentialResponse(String id, String host, String protocol, String username, String publicKey, Instant createdAt)
	{
		static CredentialResponse from(CredentialRef ref)
		{
			return new CredentialResponse(ref.id().asString(), ref.host(), ref.protocol(), ref.username(), ref.publicKey(), ref.createdAt());
		}
	}

	private final CredentialService credentialService;

	public CredentialController(CredentialService credentialService)
	{
		this.credentialService = credentialService;
	}

	@GetMapping
	public List<CredentialResponse> list()
	{
		return credentialService.list().stream().map(CredentialResponse::from).toList();
	}

	@PostMapping
	public CredentialResponse save(@RequestBody SaveRequest request)
	{
		// A null secret would NPE on toCharArray below; catch it here as a clean 400.
		// Host, protocol and an empty secret are validated by the service itself.
		if(request == null || request.secret() == null)
		{
			throw new IllegalArgumentException("Request body must contain a 'secret' field");
		}
		CredentialRef saved = credentialService.save(request.host(), request.protocol(), request.username(), request.secret().toCharArray());
		return CredentialResponse.from(saved);
	}

	/**
	 * Generates an SSH key for a host and returns its public half.
	 *
	 * <p>Unlike {@link #save}, nothing secret travels inbound either: the private key is
	 * created here and goes straight to the OS store without ever leaving the backend.</p>
	 */
	@PostMapping("/ssh-keys")
	public CredentialResponse createSshKey(@RequestBody CreateSshKeyRequest request)
	{
		if(request == null)
		{
			throw new IllegalArgumentException("Request body must contain a 'host' field");
		}
		return CredentialResponse.from(credentialService.createSshKey(request.host(), request.username(), request.comment()));
	}

	@DeleteMapping("/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void delete(@PathVariable String id)
	{
		credentialService.delete(CredentialId.of(id));
	}
}

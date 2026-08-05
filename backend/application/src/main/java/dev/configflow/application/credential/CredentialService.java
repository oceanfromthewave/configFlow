package dev.configflow.application.credential;

import dev.configflow.domain.credential.Credential;
import dev.configflow.domain.credential.CredentialId;
import dev.configflow.domain.credential.CredentialRef;
import dev.configflow.domain.credential.CredentialRefStore;
import dev.configflow.domain.credential.CredentialStore;
import dev.configflow.domain.credential.SshKeyFactory;
import dev.configflow.domain.credential.SshKeyPair;

import java.time.Clock;
import java.util.*;

/**
 * Use case: registering, listing and removing credentials.
 *
 * <p>Every method here spans two stores — the OS keychain that holds the secret and our
 * own table that holds the reference — and the interesting part is what happens when the second one fails after the first succeeded. Both orders leave a mess;
 * they are not the same mess, and each method picks the recoverable one.</p>
 */
public final class CredentialService
{

	private final CredentialStore secrets;
	private final CredentialRefStore refs;
	private final SshKeyFactory keys;
	private final Clock clock;

	public CredentialService(CredentialStore secrets, CredentialRefStore refs, SshKeyFactory keys, Clock clock)
	{
		this.secrets = Objects.requireNonNull(secrets, "secrets");
		this.refs = Objects.requireNonNull(refs, "refs");
		this.keys = Objects.requireNonNull(keys, "keys");
		this.clock = Objects.requireNonNull(clock, "clock");
	}

	/**
	 * Registers a credential, replacing whatever was stored for the same account.
	 *
	 * <p>Replacing rather than adding, because the usual reason to enter a credential
	 * twice is a rotated token: a second row would leave the lookup choosing between a working secret and a dead one.</p>
	 *
	 * @param secret
	 * 		wiped before this returns; the caller must not reuse it
	 * @throws IllegalArgumentException
	 * 		if the host, protocol or secret is empty
	 */
	public CredentialRef save(String host, String protocol, String username, char[] secret)
	{
		if(secret == null || secret.length == 0)
		{
			throw new IllegalArgumentException("'secret' must not be empty");
		}
		return store(host, protocol, username, secret, null);
	}

	/**
	 * Generates an SSH key for a host: the private half goes to the OS store, the public half comes back for the user to paste into the server.
	 *
	 * <p>Replaces any key already registered for that host and account, for the same
	 * reason {@link #save} replaces a rotated token — a second row would only shadow the first.</p>
	 */
	public CredentialRef createSshKey(String host, String username, String comment)
	{
		SshKeyPair generated = keys.generate(comment);
		try
		{
			return store(host, "ssh", username, generated.privateKeyPem(), generated.publicKey());
		}
		finally
		{
			// store() wipes the array it was handed; this also covers the path where it
			// threw before getting that far.
			Arrays.fill(generated.privateKeyPem(), '\0');
		}
	}

	/** The two-store dance both entry points share; wipes {@code secret} before returning. */
	private CredentialRef store(String host, String protocol, String username, char[] secret, String publicKey)
	{
		try
		{
			// CredentialRef normalises host, protocol and username; build one first so
			// the lookup below asks with the same spelling the row was written with.
			CredentialRef candidate = new CredentialRef(CredentialId.newId(), host, protocol, username, "pending", publicKey, clock.instant());
			Optional<CredentialRef> existing = refs.findByTarget(candidate.host(), candidate.protocol(), candidate.username());

			// Secret first. The row has to name a key that already resolves, or a crash
			// in between leaves a credential the app lists but cannot use.
			String storeKey = secrets.store(
					new Credential(candidate.host(), candidate.protocol(), candidate.username().isEmpty() ? null : candidate.username(), secret));

			CredentialRef saved = existing
					// Keep the identity: anything already pointing at this credential by
					// id must survive its owner rotating the token.
					.map(previous -> new CredentialRef(previous.id(), candidate.host(), candidate.protocol(), candidate.username(), storeKey, publicKey,
							previous.createdAt())).orElseGet(
							() -> new CredentialRef(candidate.id(), candidate.host(), candidate.protocol(), candidate.username(), storeKey, publicKey,
									candidate.createdAt()));

			try
			{
				refs.save(saved);
			}
			catch(RuntimeException e)
			{
				// The row never landed, so nothing can ever find this secret again.
				// Leaving it would be a password sitting in the OS store forever with no
				// way to reach or remove it from here.
				secrets.delete(storeKey);
				throw e;
			}

			// Only now is the old secret unreachable. Doing this earlier would have left
			// a window where the row still named it.
			existing.ifPresent(previous -> secrets.delete(previous.storeKey()));
			return saved;
		}
		finally
		{
			// The point of char[] over String. Note the limit honestly: the value arrived
			// as a String from JSON and that copy stays on the heap until it is collected.
			Arrays.fill(secret, '\0');
		}
	}

	/** Every registered credential. None of them carries a secret. */
	public List<CredentialRef> list()
	{
		return refs.findAll();
	}

	public Optional<char[]> secretFor(String host, String protocol, String username)
	{
		if(host == null || host.isBlank())
		{
			throw new IllegalArgumentException("'host' must not be blank");
		}
		if(protocol == null || protocol.isBlank())
		{
			throw new IllegalArgumentException("'protocol' must not be blank");
		}
		return refs.findByTarget(host.trim().toLowerCase(Locale.ROOT), protocol.trim().toLowerCase(Locale.ROOT), username == null ? "" : username.trim())
				.flatMap(ref -> secrets.find(ref.storeKey())).map(Credential::secret);
	}

	/**
	 * Removes a credential from both stores.
	 *
	 * @throws NoSuchElementException
	 * 		if no credential has that id
	 */
	public void delete(CredentialId id)
	{
		CredentialRef ref = refs.findById(id).orElseThrow(() -> new NoSuchElementException("No such credential: " + id.asString()));

		// OS store first, and this order is the whole decision. If the row went first and
		// this failed, the secret would stay in the keychain with nothing left pointing at
		// it — invisible to this app and impossible to remove from it. This way a failure
		// leaves a row the user can see and delete again.
		secrets.delete(ref.storeKey());
		refs.delete(id);
	}
}

package dev.configflow.infrastructure.credential;

import com.sun.jna.LastErrorException;
import com.sun.jna.Memory;
import com.sun.jna.WString;
import dev.configflow.domain.credential.Credential;
import dev.configflow.domain.credential.CredentialStore;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;

/**
 * Real Windows Credential Manager adapter (via the {@link CredAdvapi32} JNA binding). Secrets live in the OS store, never in the database — SQLite only keeps
 * the store key.
 *
 * <p>Windows copies the blob into its own store on write, so unlike the in-memory
 * placeholder this adapter needs no defensive copy of the secret: the caller may wipe its array as soon as {@link #store} returns.</p>
 */
public final class WindowsCredentialStore implements CredentialStore
{

	private static final String TARGET_PREFIX = "configflow:";
	// host and protocol never contain a tab, so it safely packs both into Comment.
	private static final String FIELD_SEP = "\t";

	private final CredAdvapi32 lib = CredAdvapi32.INSTANCE;

	@Override
	public String store(Credential credential)
	{
		String storeKey = TARGET_PREFIX + UUID.randomUUID();
		byte[] blob = toUtf8(credential.secret());
		if(blob.length > CredAdvapi32.CRED_MAX_CREDENTIAL_BLOB_SIZE)
		{
			// A secret past the store's limit is caller input, not a system fault: reject it
			// as a 400 (IllegalArgumentException) instead of letting CredWriteW's
			// ERROR_INVALID_PARAMETER surface as a 500. Scrub the copy on the way out.
			Arrays.fill(blob, (byte) 0);
			throw new IllegalArgumentException(
					"secret exceeds the Windows credential blob limit (" + blob.length + " > "
							+ CredAdvapi32.CRED_MAX_CREDENTIAL_BLOB_SIZE + " bytes)");
		}
		Memory mem = new Memory(blob.length);
		try
		{
			mem.write(0, blob, 0, blob.length);

			CredAdvapi32.CREDENTIAL c = new CredAdvapi32.CREDENTIAL();
			c.Type = CredAdvapi32.CRED_TYPE_GENERIC;
			c.Persist = CredAdvapi32.CRED_PERSIST_LOCAL_MACHINE;
			c.TargetName = new WString(storeKey);
			c.Comment = new WString(credential.host() + FIELD_SEP + credential.protocol());
			c.UserName = new WString(credential.username() == null ? "" : credential.username());
			c.CredentialBlob = mem;
			c.CredentialBlobSize = blob.length;

			lib.CredWriteW(c, 0);
			return storeKey;
		}
		catch(LastErrorException e)
		{
			throw new IllegalStateException("CredWrite failed (" + e.getErrorCode() + ")", e);
		}
		finally
		{
			// Scrub the secret out of the temp buffers now that Windows has its own copy.
			mem.clear();
			Arrays.fill(blob, (byte) 0);
		}
	}

	@Override
	public Optional<Credential> find(String storeKey)
	{
		CredAdvapi32.PCREDENTIAL ref = new CredAdvapi32.PCREDENTIAL();
		try
		{
			lib.CredReadW(new WString(storeKey), CredAdvapi32.CRED_TYPE_GENERIC, 0, ref);
		}
		catch(LastErrorException e)
		{
			if(e.getErrorCode() == CredAdvapi32.ERROR_NOT_FOUND)
			{
				return Optional.empty();
			}
			throw new IllegalStateException("CredRead failed (" + e.getErrorCode() + ")", e);
		}

		try
		{
			CredAdvapi32.CREDENTIAL c = new CredAdvapi32.CREDENTIAL(ref.credential);
			byte[] blob = c.CredentialBlob.getByteArray(0, c.CredentialBlobSize);
			// Fresh char[] the caller owns: wiping it must not reach the OS-stored copy.
			char[] secret = fromUtf8(blob);
			Arrays.fill(blob, (byte) 0);

			String user = c.UserName == null ? "" : c.UserName.toString();
			String[] hp = (c.Comment == null ? "" : c.Comment.toString()).split(FIELD_SEP, 2);
			String host = hp[0];
			String protocol = hp.length > 1 ? hp[1] : "";

			return Optional.of(new Credential(host, protocol, user.isEmpty() ? null : user, secret));
		}
		finally
		{
			// Always release the system-allocated buffer, even if mapping above threw.
			lib.CredFree(ref.credential);
		}
	}

	@Override
	public void delete(String storeKey)
	{
		try
		{
			lib.CredDeleteW(new WString(storeKey), CredAdvapi32.CRED_TYPE_GENERIC, 0);
		}
		catch(LastErrorException e)
		{
			// Idempotent: deleting an absent credential is a no-op, matching InMemory.
			if(e.getErrorCode() == CredAdvapi32.ERROR_NOT_FOUND)
			{
				return;
			}
			throw new IllegalStateException("CredDelete failed (" + e.getErrorCode() + ")", e);
		}
	}

	private static byte[] toUtf8(char[] chars)
	{
		ByteBuffer bb = StandardCharsets.UTF_8.encode(CharBuffer.wrap(chars));
		byte[] out = new byte[bb.remaining()];
		bb.get(out);
		return out;
	}

	private static char[] fromUtf8(byte[] bytes)
	{
		CharBuffer cb = StandardCharsets.UTF_8.decode(ByteBuffer.wrap(bytes));
		char[] out = new char[cb.remaining()];
		cb.get(out);
		return out;
	}
}
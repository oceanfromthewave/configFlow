package dev.configflow.infrastructure.git;

import dev.configflow.domain.credential.Credential;
import dev.configflow.domain.credential.RemoteCredentialResolver;
import org.apache.sshd.common.NamedResource;
import org.apache.sshd.common.config.keys.FilePasswordProvider;
import org.apache.sshd.common.util.security.SecurityUtils;
import org.eclipse.jgit.api.TransportConfigCallback;
import org.eclipse.jgit.transport.SshTransport;
import org.eclipse.jgit.transport.sshd.SshdSessionFactory;
import org.eclipse.jgit.transport.sshd.SshdSessionFactoryBuilder;
import org.eclipse.jgit.util.FS;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Teaches JGit to authenticate with the SSH keys this app stores.
 *
 * <p>JGit ships no SSH transport of its own, and the one it can load reads keys from
 * {@code ~/.ssh}. Ours live in the OS keychain instead, so they are handed over in memory and never written to disk.</p>
 *
 * <p>Every stored key is offered rather than the one matching the host: SSH publickey
 * auth works by the client presenting identities until the server accepts one, and the point at which sshd asks for them does not say which host it is talking
 * to.</p>
 */
final class GitSshAuth
{
	private final RemoteCredentialResolver credentials;
	private final TransportConfigCallback callback;

	GitSshAuth(RemoteCredentialResolver credentials)
	{
		this.credentials = credentials;
		SshdSessionFactory factory = new SshdSessionFactoryBuilder()
				// No password fallback: this app has no shell to prompt from, and an
				// interactive attempt would hang the operation instead of failing it.
				.setPreferredAuthentications("publickey").setHomeDirectory(FS.DETECTED.userHome())
				// Host keys still come from ~/.ssh/known_hosts, so an unknown server is
				// refused rather than trusted. Managing that file is its own slice.
				.setSshDirectory(new File(FS.DETECTED.userHome(), ".ssh"))
				// Called per session, so a key added after startup is picked up without one.
				.setDefaultKeysProvider(ignored -> storedKeys()).build(null);
		this.callback = transport -> {
			if(transport instanceof SshTransport ssh)
			{
				ssh.setSshSessionFactory(factory);
			}
		};
	}

	/** Install on every remote command; harmless on HTTPS, which is not an {@link SshTransport}. */
	TransportConfigCallback callback()
	{
		return callback;
	}

	/**
	 * Parses every stored SSH key into a usable pair.
	 *
	 * <p>A key that will not parse is skipped rather than thrown: one unreadable entry
	 * must not stop the others from being offered.</p>
	 */
	private List<KeyPair> storedKeys()
	{
		List<KeyPair> pairs = new ArrayList<>();
		for(Credential credential : credentials.resolveAll("ssh"))
		{
			byte[] pem = toUtf8(credential.secret());
			try
			{
				SecurityUtils.loadKeyPairIdentities(null, NamedResource.ofName(credential.host()), new ByteArrayInputStream(pem), FilePasswordProvider.EMPTY)
						.forEach(pairs::add);
			}
			catch(Exception ignored)
			{
				// Not ours to fix here; the key simply is not offered.
			}
			finally
			{
				Arrays.fill(pem, (byte) 0);
				Arrays.fill(credential.secret(), '\0');
			}
		}
		return pairs;
	}

	private static byte[] toUtf8(char[] chars)
	{
		var buffer = StandardCharsets.UTF_8.encode(CharBuffer.wrap(chars));
		byte[] bytes = new byte[buffer.remaining()];
		buffer.get(bytes);
		return bytes;
	}
}

package dev.configflow.infrastructure.credential;

import dev.configflow.domain.credential.SshKeyFactory;
import dev.configflow.domain.credential.SshKeyPair;
import org.apache.sshd.common.config.keys.KeyUtils;
import org.apache.sshd.common.config.keys.PublicKeyEntry;
import org.apache.sshd.common.config.keys.writer.openssh.OpenSSHKeyPairResourceWriter;
import org.apache.sshd.common.keyprovider.KeyPairProvider;

import java.io.ByteArrayOutputStream;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.util.Arrays;

public final class SshdSshKeyFactory implements SshKeyFactory
{
	@Override
	public SshKeyPair generate(String comment)
	{
		String label = comment == null || comment.isBlank() ? "configflow" : comment.trim();
		try
		{
			KeyPair pair = KeyUtils.generateKeyPair(KeyPairProvider.SSH_ED25519, 256);
			return new SshKeyPair(privateKeyOf(pair, label), PublicKeyEntry.toString(pair.getPublic()) + " " + label);
		}
		catch(GeneralSecurityException e)
		{
			// Ed25519 needs BouncyCastle on the classpath; without it sshd reports no
			// decoder for the key type, which is a deployment fault rather than bad input.
			throw new IllegalStateException("Failed to generate an Ed25519 key pair", e);
		}
		catch(java.io.IOException e)
		{
			throw new IllegalStateException("Failed to serialise the generated key pair", e);
		}
	}

	private static char[] privateKeyOf(KeyPair pair, String comment) throws java.io.IOException, GeneralSecurityException
	{
		ByteArrayOutputStream out = new ByteArrayOutputStream();

		OpenSSHKeyPairResourceWriter.INSTANCE.writePrivateKey(pair, comment, null, out);
		byte[] pem = out.toByteArray();
		try
		{
			CharBuffer chars = StandardCharsets.UTF_8.decode(java.nio.ByteBuffer.wrap(pem));
			char[] result = Arrays.copyOf(chars.array(), chars.limit());
			Arrays.fill(chars.array(), '\0');
			return result;
		}
		finally
		{
			Arrays.fill(pem, (byte) 0);
		}
	}
}

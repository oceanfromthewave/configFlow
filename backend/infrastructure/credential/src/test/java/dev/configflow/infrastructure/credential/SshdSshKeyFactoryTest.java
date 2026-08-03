package dev.configflow.infrastructure.credential;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.configflow.domain.credential.SshKeyPair;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import org.apache.sshd.common.NamedResource;
import org.apache.sshd.common.config.keys.FilePasswordProvider;
import org.apache.sshd.common.util.security.SecurityUtils;
import org.junit.jupiter.api.Test;

class SshdSshKeyFactoryTest {

    private final SshdSshKeyFactory factory = new SshdSshKeyFactory();

    @Test
    void generatesAnEd25519KeyWithTheCommentAppended() {
        SshKeyPair pair = factory.generate("git@laptop");

        assertTrue(pair.publicKey().startsWith("ssh-ed25519 "));
        assertTrue(pair.publicKey().endsWith(" git@laptop"));
    }

    @Test
    void blankCommentFallsBackToADefault() {
        SshKeyPair pair = factory.generate("  ");

        assertTrue(pair.publicKey().endsWith(" configflow"));
    }

    @Test
    void thePrivateKeyActuallyParsesBackToTheSamePublicKey() throws Exception {
        SshKeyPair pair = factory.generate("git@laptop");

        byte[] pem = new String(pair.privateKeyPem()).getBytes(StandardCharsets.UTF_8);
        Iterable<KeyPair> loaded = SecurityUtils.loadKeyPairIdentities(
                null, NamedResource.ofName("test"), new ByteArrayInputStream(pem), FilePasswordProvider.EMPTY);
        KeyPair parsed = loaded.iterator().next();

        assertEquals(
                org.apache.sshd.common.config.keys.PublicKeyEntry.toString(parsed.getPublic()),
                pair.publicKey().substring(0, pair.publicKey().lastIndexOf(' ')));
    }

    @Test
    void differentCallsProduceDifferentKeys() {
        SshKeyPair first = factory.generate("a");
        SshKeyPair second = factory.generate("b");

        assertTrue(!first.publicKey().equals(second.publicKey()));
    }
}

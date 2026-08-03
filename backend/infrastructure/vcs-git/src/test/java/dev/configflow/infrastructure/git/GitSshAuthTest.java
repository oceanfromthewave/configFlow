package dev.configflow.infrastructure.git;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import dev.configflow.domain.credential.Credential;
import dev.configflow.domain.credential.RemoteCredentialResolver;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class GitSshAuthTest {

    @Test
    void buildsATransportConfigCallbackEvenWithNoStoredKeys() {
        GitSshAuth auth = new GitSshAuth(new RemoteCredentialResolver() {
            @Override
            public Optional<Credential> resolve(String host, String protocol) {
                return Optional.empty();
            }

            @Override
            public List<Credential> resolveAll(String protocol) {
                return List.of();
            }
        });

        assertNotNull(auth.callback());
    }

    @Test
    void aMalformedStoredKeyIsSkippedRatherThanFailingConstruction() {
        // The resolver hands back garbage instead of a real PEM; GitSshAuth must not
        // throw building the callback since the keys provider is only invoked lazily
        // per SSH session, not eagerly here.
        assertDoesNotThrow(() -> new GitSshAuth(new RemoteCredentialResolver() {
            @Override
            public Optional<Credential> resolve(String host, String protocol) {
                return Optional.empty();
            }

            @Override
            public List<Credential> resolveAll(String protocol) {
                return List.of(new Credential("github.com", "ssh", "git", "not a real key".toCharArray()));
            }
        }));
    }
}

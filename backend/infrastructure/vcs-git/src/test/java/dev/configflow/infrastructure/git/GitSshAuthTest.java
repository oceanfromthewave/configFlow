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
    void opensASessionEvenWithNoStoredKeys() {
        GitSshAuth auth = new GitSshAuth(new RemoteCredentialResolver() {
            @Override
            public Optional<Credential> resolve(String host, String protocol) {
                return Optional.empty();
            }

            @Override
            public List<Credential> resolveAll(String host, String protocol) {
                return List.of();
            }
        });

        try (GitSshAuth.Session session = auth.open("github.com")) {
            assertNotNull(session.callback());
        }
    }

    @Test
    void aMalformedStoredKeyIsSkippedRatherThanFailingConstruction() {
        // The resolver hands back garbage instead of a real PEM; opening a session must
        // not throw, since the keys provider is only invoked lazily per SSH session, not
        // eagerly here.
        RemoteCredentialResolver garbage = new RemoteCredentialResolver() {
            @Override
            public Optional<Credential> resolve(String host, String protocol) {
                return Optional.empty();
            }

            @Override
            public List<Credential> resolveAll(String host, String protocol) {
                return List.of(new Credential("github.com", "ssh", "git", "not a real key".toCharArray()));
            }
        };

        assertDoesNotThrow(() -> {
            try (GitSshAuth.Session session = new GitSshAuth(garbage).open("github.com")) {
                assertNotNull(session.callback());
            }
        });
    }
}

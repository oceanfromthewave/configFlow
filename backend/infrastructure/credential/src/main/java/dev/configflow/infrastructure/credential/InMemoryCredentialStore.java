package dev.configflow.infrastructure.credential;

import dev.configflow.domain.credential.Credential;
import dev.configflow.domain.credential.CredentialStore;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * M0 placeholder credential store keeping secrets in process memory only.
 *
 * <p>Replaced in a later milestone by the real OS adapters (Windows Credential
 * Manager / macOS Keychain / libsecret). Secrets never touch the database either
 * way — SQLite only holds the store key.</p>
 */
public final class InMemoryCredentialStore implements CredentialStore {

    private final Map<String, Credential> secrets = new ConcurrentHashMap<>();

    @Override
    public String store(Credential credential) {
        String storeKey = "mem:" + UUID.randomUUID();
        secrets.put(storeKey, credential);
        return storeKey;
    }

    @Override
    public Optional<Credential> find(String storeKey) {
        return Optional.ofNullable(secrets.get(storeKey));
    }

    @Override
    public void delete(String storeKey) {
        secrets.remove(storeKey);
    }
}

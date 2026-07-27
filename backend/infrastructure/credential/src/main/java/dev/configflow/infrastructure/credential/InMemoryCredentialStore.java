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
        // Copy the secret: the caller wipes its array right after store() returns
        // (see CredentialStore#store), so a kept reference would be zeroed out too.
        // A real OS adapter escapes this by copying the bytes into the keychain.
        Credential copy = new Credential(
                credential.host(), credential.protocol(), credential.username(),
                credential.secret().clone());
        secrets.put(storeKey, copy);
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

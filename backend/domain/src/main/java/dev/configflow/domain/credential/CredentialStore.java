package dev.configflow.domain.credential;

import java.util.Optional;

/**
 * Port to the OS credential store (Windows Credential Manager, macOS Keychain,
 * libsecret). The database only stores the returned {@code storeKey}.
 */
public interface CredentialStore {

    /**
     * Stores a credential and returns the opaque key used to retrieve it later.
     *
     * <p>The caller wipes {@code credential.secret()} right after this returns, so an
     * implementation that keeps the secret beyond the call must copy it, not retain the
     * passed array.</p>
     */
    String store(Credential credential);

    /**
     * Retrieves a credential by its store key.
     *
     * <p>The returned secret is the caller's to wipe, so an implementation must hand
     * back a copy rather than its retained array.</p>
     */
    Optional<Credential> find(String storeKey);

    /** Removes a credential from the OS store. */
    void delete(String storeKey);
}

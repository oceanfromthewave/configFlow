package dev.configflow.domain.credential;

import java.util.Optional;

/**
 * Port to the OS credential store (Windows Credential Manager, macOS Keychain,
 * libsecret). The database only stores the returned {@code storeKey}.
 */
public interface CredentialStore {

    /** Stores a credential and returns the opaque key used to retrieve it later. */
    String store(Credential credential);

    /** Retrieves a credential by its store key. */
    Optional<Credential> find(String storeKey);

    /** Removes a credential from the OS store. */
    void delete(String storeKey);
}

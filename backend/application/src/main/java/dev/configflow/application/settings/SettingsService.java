package dev.configflow.application.settings;

import dev.configflow.domain.settings.SettingsStore;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Use case: read and update app settings.
 *
 * <p>Pure Java (no framework types) — bootstrap wires it against the SQLite
 * {@link SettingsStore} adapter, which is exactly the layering this milestone proves
 * end to end: API → application → domain port → infrastructure adapter.</p>
 */
public final class SettingsService {

    private final SettingsStore settingsStore;

    public SettingsService(SettingsStore settingsStore) {
        this.settingsStore = Objects.requireNonNull(settingsStore, "settingsStore must not be null");
    }

    /** All settings as an immutable snapshot. */
    public Map<String, String> all() {
        return settingsStore.findAll();
    }

    /** Reads one setting by key. */
    public Optional<String> get(String key) {
        requireValidKey(key);
        return settingsStore.get(key);
    }

    /** Creates or updates one setting. */
    public void put(String key, String value) {
        requireValidKey(key);
        Objects.requireNonNull(value, "value must not be null");
        settingsStore.put(key, value);
    }

    /** Deletes one setting (no-op when absent). */
    public void remove(String key) {
        requireValidKey(key);
        settingsStore.remove(key);
    }

    private static void requireValidKey(String key) {
        Objects.requireNonNull(key, "key must not be null");
        if (key.isBlank()) {
            throw new IllegalArgumentException("setting key must not be blank");
        }
    }
}

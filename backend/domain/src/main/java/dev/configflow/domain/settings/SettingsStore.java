package dev.configflow.domain.settings;

import java.util.Map;
import java.util.Optional;

/**
 * Persistence port for app settings (key-value; value typing is the application's
 * responsibility). Backed by the {@code app_setting} SQLite table.
 */
public interface SettingsStore {

    /** Reads one setting. */
    Optional<String> get(String key);

    /** Inserts or updates one setting. */
    void put(String key, String value);

    /** Deletes one setting (no-op when absent). */
    void remove(String key);

    /** All settings as an immutable snapshot. */
    Map<String, String> findAll();
}
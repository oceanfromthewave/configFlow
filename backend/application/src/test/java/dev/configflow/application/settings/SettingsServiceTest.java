package dev.configflow.application.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.configflow.domain.settings.SettingsStore;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Unit test with an in-memory fake store — demonstrates that the application layer
 * is testable without any framework or database.
 */
class SettingsServiceTest {

    /** Simple in-memory fake of the persistence port. */
    private static final class FakeSettingsStore implements SettingsStore {
        private final Map<String, String> data = new HashMap<>();

        @Override
        public Optional<String> get(String key) {
            return Optional.ofNullable(data.get(key));
        }

        @Override
        public void put(String key, String value) {
            data.put(key, value);
        }

        @Override
        public void remove(String key) {
            data.remove(key);
        }

        @Override
        public Map<String, String> findAll() {
            return Map.copyOf(data);
        }
    }

    private final SettingsService service = new SettingsService(new FakeSettingsStore());

    @Test
    void putThenGetRoundTrips() {
        service.put("theme", "dark");

        assertEquals(Optional.of("dark"), service.get("theme"));
        assertEquals(Map.of("theme", "dark"), service.all());
    }

    @Test
    void removeDeletesKey() {
        service.put("language", "ko");
        service.remove("language");

        assertTrue(service.get("language").isEmpty());
    }

    @Test
    void blankKeyIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> service.put("  ", "x"));
        assertThrows(IllegalArgumentException.class, () -> service.get(""));
    }
}

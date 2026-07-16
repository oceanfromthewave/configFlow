package dev.configflow.api.settings;

import dev.configflow.application.settings.SettingsService;
import java.util.Map;
import java.util.NoSuchElementException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Settings API — the first endpoint wired through the complete layer stack:
 * controller → application {@link SettingsService} → domain {@code SettingsStore}
 * port → SQLite adapter.
 */
@RestController
@RequestMapping("/api/v1/settings")
public class SettingsController {

    /** PUT request body. */
    public record SettingUpdateRequest(String value) {}

    /** Single-setting response. */
    public record SettingResponse(String key, String value) {}

    private final SettingsService settingsService;

    public SettingsController(SettingsService settingsService) {
        this.settingsService = settingsService;
    }

    @GetMapping
    public Map<String, String> all() {
        return settingsService.all();
    }

    @GetMapping("/{key}")
    public SettingResponse get(@PathVariable String key) {
        String value = settingsService.get(key)
                .orElseThrow(() -> new NoSuchElementException("Setting not found: " + key));
        return new SettingResponse(key, value);
    }

    @PutMapping("/{key}")
    public SettingResponse put(@PathVariable String key, @RequestBody SettingUpdateRequest request) {
        if (request == null || request.value() == null) {
            throw new IllegalArgumentException("Request body must contain a 'value' field");
        }
        settingsService.put(key, request.value());
        return new SettingResponse(key, request.value());
    }
}

package dev.configflow.api.settings;

import dev.configflow.application.settings.ProxyService;
import dev.configflow.application.settings.SettingsService;
import dev.configflow.domain.settings.ProxySettings;
import java.util.Map;
import java.util.NoSuchElementException;
import org.springframework.web.bind.annotation.DeleteMapping;
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

    /** PUT /proxy request body. */
    public record ProxyUpdateRequest(String url, String bypass) {}

    /** Proxy response; {@code url} is null when no proxy is configured. */
    public record ProxyResponse(String url, String bypass) {}

    private final SettingsService settingsService;
    private final ProxyService proxyService;

    public SettingsController(SettingsService settingsService, ProxyService proxyService) {
        this.settingsService = settingsService;
        this.proxyService = proxyService;
    }

    @GetMapping
    public Map<String, String> all() {
        return settingsService.all();
    }

    /** Reads the proxy. Not configured is a normal state, so this answers 200 with a null URL. */
    @GetMapping("/proxy")
    public ProxyResponse proxy() {
        return proxyService.current()
                .map(proxy -> new ProxyResponse(proxy.url(), String.join(",", proxy.bypass())))
                .orElseGet(() -> new ProxyResponse(null, ""));
    }

    @PutMapping("/proxy")
    public ProxyResponse putProxy(@RequestBody ProxyUpdateRequest request) {
        if (request == null || request.url() == null || request.url().isBlank()) {
            throw new IllegalArgumentException("Request body must contain a 'url' field");
        }
        ProxySettings proxy = proxyService.update(request.url(), request.bypass());
        return new ProxyResponse(proxy.url(), String.join(",", proxy.bypass()));
    }

    /** Removes the proxy; connections go direct again. */
    @DeleteMapping("/proxy")
    public ProxyResponse deleteProxy() {
        proxyService.clear();
        return new ProxyResponse(null, "");
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

package dev.configflow.api.health;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Liveness endpoint the Electron launcher polls during the startup handshake.
 * Token-protected like every other API path.
 */
@RestController
@RequestMapping("/api/v1/health")
public class HealthController {

    /** Backend version reported to the shell. */
    static final String VERSION = "0.1.0";

    @GetMapping
    public Map<String, String> health() {
        return Map.of("status", "UP", "version", VERSION);
    }
}

package dev.configflow;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * ConfigFlow backend entry point.
 *
 * <p>Launched by the Electron main process with
 * {@code --server.port=<port> --configflow.token=<token>}. Binds to 127.0.0.1 only
 * (see {@code application.properties}); every {@code /api/**} request must present
 * the session token. When no token argument is given (developer mode) the default
 * {@code dev-token} applies.</p>
 */
@SpringBootApplication
@EnableScheduling
public class ConfigFlowApplication {

    public static void main(String[] args) {
        SpringApplication.run(ConfigFlowApplication.class, args);
    }
}

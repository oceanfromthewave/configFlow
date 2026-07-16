package dev.configflow.config;

import dev.configflow.application.settings.SettingsService;
import dev.configflow.domain.operation.OperationHistoryStore;
import dev.configflow.domain.repository.RepositoryStore;
import dev.configflow.domain.settings.SettingsStore;
import dev.configflow.infrastructure.persistence.SqliteDatabase;
import dev.configflow.infrastructure.persistence.SqliteOperationHistoryStore;
import dev.configflow.infrastructure.persistence.SqliteRepositoryStore;
import dev.configflow.infrastructure.persistence.SqliteSettingsStore;
import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Assembles the SQLite persistence adapters and the application-layer services that
 * consume them (dependency rule: only bootstrap knows both sides).
 *
 * <p>The database location comes from {@code configflow.db.path}; when empty the
 * default {@code %USERPROFILE%\.configflow\configflow.db} is used. Migrations run
 * eagerly at startup.</p>
 */
@Configuration
public class PersistenceConfig {

    @Bean
    public SqliteDatabase sqliteDatabase(@Value("${configflow.db.path:}") String dbPath) {
        Path path = (dbPath == null || dbPath.isBlank())
                ? SqliteDatabase.defaultPath()
                : Path.of(dbPath);
        SqliteDatabase database = new SqliteDatabase(path);
        database.migrate();
        return database;
    }

    @Bean
    public RepositoryStore repositoryStore(SqliteDatabase database) {
        return new SqliteRepositoryStore(database);
    }

    @Bean
    public SettingsStore settingsStore(SqliteDatabase database) {
        return new SqliteSettingsStore(database);
    }

    @Bean
    public OperationHistoryStore operationHistoryStore(SqliteDatabase database) {
        return new SqliteOperationHistoryStore(database);
    }

    @Bean
    public SettingsService settingsService(SettingsStore settingsStore) {
        return new SettingsService(settingsStore);
    }
}

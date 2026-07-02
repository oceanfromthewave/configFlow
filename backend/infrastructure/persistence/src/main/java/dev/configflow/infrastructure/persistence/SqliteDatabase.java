package dev.configflow.infrastructure.persistence;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;

/**
 * Owns the SQLite database file: builds the {@link DataSource} and runs Flyway
 * migrations ({@code db/migration/V*.sql}).
 *
 * <p>The database path is a constructor argument so tests can point at a temp file;
 * production uses {@link #defaultPath()} ({@code %USERPROFILE%\.configflow\configflow.db}).</p>
 */
public final class SqliteDatabase {

    private final Path dbPath;
    private final SQLiteDataSource dataSource;

    public SqliteDatabase(Path dbPath) {
        this.dbPath = Objects.requireNonNull(dbPath, "dbPath must not be null").toAbsolutePath();
        ensureParentDirectory(this.dbPath);

        SQLiteConfig config = new SQLiteConfig();
        config.enforceForeignKeys(true);
        this.dataSource = new SQLiteDataSource(config);
        this.dataSource.setUrl("jdbc:sqlite:" + this.dbPath);
    }

    /** Default production location: {@code ~/.configflow/configflow.db}. */
    public static Path defaultPath() {
        return Path.of(System.getProperty("user.home"), ".configflow", "configflow.db");
    }

    /** Applies all pending Flyway migrations. Safe to call repeatedly (idempotent). */
    public void migrate() {
        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }

    /** Opens a new connection; callers close it (try-with-resources). */
    public Connection openConnection() throws SQLException {
        return dataSource.getConnection();
    }

    /** Absolute path of the database file. */
    public Path path() {
        return dbPath;
    }

    private static void ensureParentDirectory(Path dbPath) {
        Path parent = dbPath.getParent();
        if (parent == null) {
            return;
        }
        try {
            Files.createDirectories(parent);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot create database directory: " + parent, e);
        }
    }
}

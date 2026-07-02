package dev.configflow.infrastructure.persistence;

import dev.configflow.domain.settings.SettingsStore;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Plain-JDBC SQLite adapter for the {@link SettingsStore} port
 * ({@code app_setting} table).
 */
public final class SqliteSettingsStore implements SettingsStore {

    private final SqliteDatabase database;

    public SqliteSettingsStore(SqliteDatabase database) {
        this.database = Objects.requireNonNull(database, "database must not be null");
    }

    @Override
    public Optional<String> get(String key) {
        try (Connection con = database.openConnection();
                PreparedStatement ps = con.prepareStatement(
                        "SELECT value FROM app_setting WHERE key = ?")) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(rs.getString(1)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new PersistenceException("Failed to read setting " + key, e);
        }
    }

    @Override
    public void put(String key, String value) {
        String sql = """
                INSERT INTO app_setting (key, value, updated_at) VALUES (?, ?, ?)
                ON CONFLICT(key) DO UPDATE SET value = excluded.value, updated_at = excluded.updated_at
                """;
        try (Connection con = database.openConnection();
                PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, key);
            ps.setString(2, value);
            ps.setString(3, Instant.now().toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new PersistenceException("Failed to write setting " + key, e);
        }
    }

    @Override
    public void remove(String key) {
        try (Connection con = database.openConnection();
                PreparedStatement ps = con.prepareStatement("DELETE FROM app_setting WHERE key = ?")) {
            ps.setString(1, key);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new PersistenceException("Failed to delete setting " + key, e);
        }
    }

    @Override
    public Map<String, String> findAll() {
        try (Connection con = database.openConnection();
                PreparedStatement ps = con.prepareStatement(
                        "SELECT key, value FROM app_setting ORDER BY key");
                ResultSet rs = ps.executeQuery()) {
            Map<String, String> result = new LinkedHashMap<>();
            while (rs.next()) {
                result.put(rs.getString(1), rs.getString(2));
            }
            return Map.copyOf(result);
        } catch (SQLException e) {
            throw new PersistenceException("Failed to list settings", e);
        }
    }
}

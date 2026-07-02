package dev.configflow.infrastructure.persistence;

import dev.configflow.domain.repository.Repository;
import dev.configflow.domain.repository.RepositoryId;
import dev.configflow.domain.repository.RepositoryStore;
import dev.configflow.domain.vcs.model.VcsType;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Plain-JDBC SQLite adapter for the {@link RepositoryStore} port
 * ({@code repository} table).
 */
public final class SqliteRepositoryStore implements RepositoryStore {

    private static final String UPSERT = """
            INSERT INTO repository
              (id, name, local_path, remote_url, vcs_type, group_name, is_favorite, created_at, last_opened_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(id) DO UPDATE SET
              name = excluded.name,
              local_path = excluded.local_path,
              remote_url = excluded.remote_url,
              vcs_type = excluded.vcs_type,
              group_name = excluded.group_name,
              is_favorite = excluded.is_favorite,
              last_opened_at = excluded.last_opened_at
            """;

    private static final String SELECT = """
            SELECT id, name, local_path, remote_url, vcs_type, group_name, is_favorite, created_at, last_opened_at
            FROM repository
            """;

    private final SqliteDatabase database;

    public SqliteRepositoryStore(SqliteDatabase database) {
        this.database = Objects.requireNonNull(database, "database must not be null");
    }

    @Override
    public void save(Repository repository) {
        try (Connection con = database.openConnection();
                PreparedStatement ps = con.prepareStatement(UPSERT)) {
            ps.setString(1, repository.id().asString());
            ps.setString(2, repository.name());
            ps.setString(3, repository.localPath().toString());
            ps.setString(4, repository.remoteUrl());
            ps.setString(5, repository.vcsType().name());
            ps.setString(6, repository.groupName());
            ps.setInt(7, repository.favorite() ? 1 : 0);
            ps.setString(8, repository.createdAt().toString());
            ps.setString(9, repository.lastOpenedAt() == null ? null : repository.lastOpenedAt().toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new PersistenceException("Failed to save repository " + repository.id().asString(), e);
        }
    }

    @Override
    public Optional<Repository> findById(RepositoryId id) {
        return queryOne(SELECT + " WHERE id = ?", id.asString());
    }

    @Override
    public Optional<Repository> findByLocalPath(Path localPath) {
        return queryOne(SELECT + " WHERE local_path = ?", localPath.toString());
    }

    @Override
    public List<Repository> findAll() {
        String sql = SELECT + " ORDER BY last_opened_at DESC NULLS LAST, created_at DESC";
        try (Connection con = database.openConnection();
                PreparedStatement ps = con.prepareStatement(sql);
                ResultSet rs = ps.executeQuery()) {
            List<Repository> result = new ArrayList<>();
            while (rs.next()) {
                result.add(map(rs));
            }
            return List.copyOf(result);
        } catch (SQLException e) {
            throw new PersistenceException("Failed to list repositories", e);
        }
    }

    @Override
    public void delete(RepositoryId id) {
        try (Connection con = database.openConnection();
                PreparedStatement ps = con.prepareStatement("DELETE FROM repository WHERE id = ?")) {
            ps.setString(1, id.asString());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new PersistenceException("Failed to delete repository " + id.asString(), e);
        }
    }

    private Optional<Repository> queryOne(String sql, String argument) {
        try (Connection con = database.openConnection();
                PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, argument);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new PersistenceException("Failed to load repository", e);
        }
    }

    private static Repository map(ResultSet rs) throws SQLException {
        String lastOpenedAt = rs.getString("last_opened_at");
        return new Repository(
                RepositoryId.of(rs.getString("id")),
                rs.getString("name"),
                Path.of(rs.getString("local_path")),
                rs.getString("remote_url"),
                VcsType.valueOf(rs.getString("vcs_type")),
                rs.getString("group_name"),
                rs.getInt("is_favorite") != 0,
                Instant.parse(rs.getString("created_at")),
                lastOpenedAt == null ? null : Instant.parse(lastOpenedAt));
    }
}

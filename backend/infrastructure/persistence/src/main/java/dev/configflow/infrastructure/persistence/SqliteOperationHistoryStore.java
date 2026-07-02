package dev.configflow.infrastructure.persistence;

import dev.configflow.domain.operation.Operation;
import dev.configflow.domain.operation.OperationHistoryStore;
import dev.configflow.domain.operation.OperationId;
import dev.configflow.domain.operation.OperationState;
import dev.configflow.domain.operation.OperationType;
import dev.configflow.domain.repository.RepositoryId;
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
 * Plain-JDBC SQLite adapter for the {@link OperationHistoryStore} port
 * ({@code operation_history} table).
 *
 * <p>The console log is stored newline-joined and capped at {@value #MAX_LOG_BYTES}
 * bytes per operation (per docs/05 schema notes). Progress is transient and not
 * persisted.</p>
 */
public final class SqliteOperationHistoryStore implements OperationHistoryStore {

    /** Upper bound of the persisted log column, per data-model design notes. */
    static final int MAX_LOG_BYTES = 64 * 1024;

    private static final String UPSERT = """
            INSERT INTO operation_history
              (id, repository_id, type, state, started_at, finished_at, error_message, log)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(id) DO UPDATE SET
              state = excluded.state,
              started_at = excluded.started_at,
              finished_at = excluded.finished_at,
              error_message = excluded.error_message,
              log = excluded.log
            """;

    private static final String SELECT = """
            SELECT id, repository_id, type, state, started_at, finished_at, error_message, log
            FROM operation_history
            """;

    private final SqliteDatabase database;

    public SqliteOperationHistoryStore(SqliteDatabase database) {
        this.database = Objects.requireNonNull(database, "database must not be null");
    }

    @Override
    public void save(Operation operation) {
        try (Connection con = database.openConnection();
                PreparedStatement ps = con.prepareStatement(UPSERT)) {
            ps.setString(1, operation.id().asString());
            ps.setString(2, operation.repositoryId() == null ? null : operation.repositoryId().asString());
            ps.setString(3, operation.type().name());
            ps.setString(4, operation.state().name());
            ps.setString(5, (operation.startedAt() == null ? Instant.now() : operation.startedAt()).toString());
            ps.setString(6, operation.finishedAt() == null ? null : operation.finishedAt().toString());
            ps.setString(7, operation.errorMessage());
            ps.setString(8, joinLog(operation.logLines()));
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new PersistenceException("Failed to save operation " + operation.id().asString(), e);
        }
    }

    @Override
    public Optional<Operation> findById(OperationId id) {
        try (Connection con = database.openConnection();
                PreparedStatement ps = con.prepareStatement(SELECT + " WHERE id = ?")) {
            ps.setString(1, id.asString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new PersistenceException("Failed to load operation " + id.asString(), e);
        }
    }

    @Override
    public List<Operation> findRecent(RepositoryId repositoryId, int limit) {
        String sql = SELECT + " WHERE repository_id = ? ORDER BY started_at DESC LIMIT ?";
        try (Connection con = database.openConnection();
                PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, repositoryId.asString());
            ps.setInt(2, limit);
            try (ResultSet rs = ps.executeQuery()) {
                List<Operation> result = new ArrayList<>();
                while (rs.next()) {
                    result.add(map(rs));
                }
                return List.copyOf(result);
            }
        } catch (SQLException e) {
            throw new PersistenceException("Failed to list operations", e);
        }
    }

    private static String joinLog(List<String> logLines) {
        if (logLines.isEmpty()) {
            return null;
        }
        String joined = String.join("\n", logLines);
        if (joined.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_LOG_BYTES) {
            // Keep the tail: the end of a log is the interesting part after a failure.
            joined = joined.substring(Math.max(0, joined.length() - MAX_LOG_BYTES));
        }
        return joined;
    }

    private static Operation map(ResultSet rs) throws SQLException {
        String repositoryId = rs.getString("repository_id");
        String finishedAt = rs.getString("finished_at");
        String log = rs.getString("log");
        return new Operation(
                OperationId.of(rs.getString("id")),
                repositoryId == null ? null : RepositoryId.of(repositoryId),
                OperationType.valueOf(rs.getString("type")),
                OperationState.valueOf(rs.getString("state")),
                null,
                Instant.parse(rs.getString("started_at")),
                finishedAt == null ? null : Instant.parse(finishedAt),
                rs.getString("error_message"),
                log == null ? List.of() : List.of(log.split("\n", -1)));
    }
}

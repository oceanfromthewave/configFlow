package dev.configflow.infrastructure.persistence;

import dev.configflow.domain.credential.CredentialId;
import dev.configflow.domain.credential.CredentialRef;
import dev.configflow.domain.credential.CredentialRefStore;

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
 * Plain-JDBC SQLite adapter for the {@link CredentialRefStore} port ({@code credential_ref} table). Stores everything about a credential except the secret,
 * which lives in the OS store behind {@link CredentialRef#storeKey()}.
 */
public final class SqliteCredentialRefStore implements CredentialRefStore
{

	private static final String UPSERT = """
			INSERT INTO credential_ref
			  (id, host, protocol, username, store_key, created_at)
			VALUES (?, ?, ?, ?, ?, ?)
			ON CONFLICT(id) DO UPDATE SET
			  host = excluded.host,
			  protocol = excluded.protocol,
			  username = excluded.username,
			  store_key = excluded.store_key
			""";

	private static final String SELECT = """
			SELECT id, host, protocol, username, store_key, created_at
			FROM credential_ref
			""";

	private final SqliteDatabase database;

	public SqliteCredentialRefStore(SqliteDatabase database)
	{
		this.database = Objects.requireNonNull(database, "database must not be null");
	}

	@Override
	public void save(CredentialRef ref)
	{
		try(Connection con = database.openConnection(); PreparedStatement ps = con.prepareStatement(UPSERT))
		{
			ps.setString(1, ref.id().asString());
			ps.setString(2, ref.host());
			ps.setString(3, ref.protocol());
			ps.setString(4, ref.username());
			ps.setString(5, ref.storeKey());
			ps.setString(6, ref.createdAt().toString());
			ps.executeUpdate();
		}
		catch(SQLException e)
		{
			throw new PersistenceException("Failed to save credential " + ref.id().asString(), e);
		}
	}

	@Override
	public Optional<CredentialRef> findById(CredentialId id)
	{
		return queryOne(SELECT + " WHERE id = ?", ps -> ps.setString(1, id.asString()));
	}

	@Override
	public Optional<CredentialRef> findByTarget(String host, String protocol, String username)
	{
		return queryOne(SELECT + " WHERE host = ? AND protocol = ? AND username = ?", ps -> {
			ps.setString(1, host == null ? null : host.toLowerCase());
			ps.setString(2, protocol == null ? null : protocol.toLowerCase());
			ps.setString(3, username == null ? "" : username.trim());
		});
	}

	@Override
	public Optional<CredentialRef> findFor(String host, String protocol)
	{
		// Newest first so a rotated token (the latest row) is chosen over the stale one.
		// Order by the parsed time, not the ISO text: Instant.toString() has a variable-width
		// fraction, so a plain string sort can rank a whole-second row above a later fractional one.
		return queryOne(SELECT + " WHERE host = ? AND protocol = ? ORDER BY julianday(created_at) DESC LIMIT 1", ps -> {
			ps.setString(1, host == null ? null : host.toLowerCase());
			ps.setString(2, protocol == null ? null : protocol.toLowerCase());
		});
	}

	@Override
	public List<CredentialRef> findAll()
	{
		// Parsed-time order (see findFor) so a variable-width ISO fraction can't misrank rows.
		String sql = SELECT + " ORDER BY julianday(created_at) DESC";
		try(Connection con = database.openConnection(); PreparedStatement ps = con.prepareStatement(sql); ResultSet rs = ps.executeQuery())
		{
			List<CredentialRef> result = new ArrayList<>();
			while(rs.next())
			{
				result.add(map(rs));
			}
			return List.copyOf(result);
		}
		catch(SQLException e)
		{
			throw new PersistenceException("Failed to list credentials", e);
		}
	}

	@Override
	public void delete(CredentialId id)
	{
		try(Connection con = database.openConnection(); PreparedStatement ps = con.prepareStatement("DELETE FROM credential_ref WHERE id = ?"))
		{
			ps.setString(1, id.asString());
			ps.executeUpdate();
		}
		catch(SQLException e)
		{
			throw new PersistenceException("Failed to delete credential " + id.asString(), e);
		}
	}

	private Optional<CredentialRef> queryOne(String sql, StatementBinder binder)
	{
		try(Connection con = database.openConnection(); PreparedStatement ps = con.prepareStatement(sql))
		{
			binder.bind(ps);
			try(ResultSet rs = ps.executeQuery())
			{
				return rs.next() ? Optional.of(map(rs)) : Optional.empty();
			}
		}
		catch(SQLException e)
		{
			throw new PersistenceException("Failed to load credential", e);
		}
	}

	private static CredentialRef map(ResultSet rs) throws SQLException
	{
		return new CredentialRef(CredentialId.of(rs.getString("id")), rs.getString("host"), rs.getString("protocol"), rs.getString("username"),
				rs.getString("store_key"), Instant.parse(rs.getString("created_at")));
	}

	@FunctionalInterface
	private interface StatementBinder
	{
		void bind(PreparedStatement ps) throws SQLException;
	}
}

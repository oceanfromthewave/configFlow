package dev.configflow.config;

import dev.configflow.application.credential.CredentialService;
import dev.configflow.application.settings.ProxyService;
import dev.configflow.application.settings.SettingsService;
import dev.configflow.domain.credential.CredentialRefStore;
import dev.configflow.domain.credential.CredentialStore;
import dev.configflow.domain.credential.SshKeyFactory;
import dev.configflow.domain.operation.OperationHistoryStore;
import dev.configflow.domain.repository.RepositoryStore;
import dev.configflow.domain.settings.SettingsStore;
import dev.configflow.infrastructure.credential.InMemoryCredentialStore;
import dev.configflow.infrastructure.credential.SshdSshKeyFactory;
import dev.configflow.infrastructure.credential.WindowsCredentialStore;
import dev.configflow.infrastructure.persistence.SqliteCredentialRefStore;
import dev.configflow.infrastructure.persistence.SqliteDatabase;
import dev.configflow.infrastructure.persistence.SqliteOperationHistoryStore;
import dev.configflow.infrastructure.persistence.SqliteRepositoryStore;
import dev.configflow.infrastructure.persistence.SqliteSettingsStore;

import java.nio.file.Path;
import java.time.Clock;
import java.util.Locale;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Assembles the SQLite persistence adapters and the application-layer services that consume them (dependency rule: only bootstrap knows both sides).
 *
 * <p>The database location comes from {@code configflow.db.path}; when empty the
 * default {@code %USERPROFILE%\.configflow\configflow.db} is used. Migrations run eagerly at startup.</p>
 */
@Configuration
public class PersistenceConfig
{

	@Bean
	public SqliteDatabase sqliteDatabase(@Value("${configflow.db.path:}") String dbPath)
	{
		Path path = (dbPath == null || dbPath.isBlank()) ? SqliteDatabase.defaultPath() : Path.of(dbPath);
		SqliteDatabase database = new SqliteDatabase(path);
		database.migrate();
		return database;
	}

	@Bean
	public RepositoryStore repositoryStore(SqliteDatabase database)
	{
		return new SqliteRepositoryStore(database);
	}

	@Bean
	public SettingsStore settingsStore(SqliteDatabase database)
	{
		return new SqliteSettingsStore(database);
	}

	@Bean
	public OperationHistoryStore operationHistoryStore(SqliteDatabase database)
	{
		return new SqliteOperationHistoryStore(database);
	}

	@Bean
	public SettingsService settingsService(SettingsStore settingsStore)
	{
		return new SettingsService(settingsStore);
	}

	/**
	 * Proxy settings. Constructing the bean applies whatever is stored, so the first outbound connection after startup already goes through the proxy.
	 */
	@Bean
	public ProxyService proxyService(SettingsStore settingsStore)
	{
		ProxyService service = new ProxyService(settingsStore);
		service.applyStored();
		return service;
	}

	@Bean
	public CredentialRefStore credentialRefStore(SqliteDatabase database)
	{
		return new SqliteCredentialRefStore(database);
	}

	/**
	 * Secrets store. In-memory for now; slice 3 swaps this one line for the Windows Credential Manager adapter, and nothing else in the wiring changes.
	 */
	@Bean
	public CredentialStore credentialStore()
	{
		String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
		if(os.contains("win"))
		{
			return new WindowsCredentialStore();
		}
		return new InMemoryCredentialStore();
	}

	/** Ed25519 key generation. Platform-independent, unlike the store the keys end up in. */
	@Bean
	public SshKeyFactory sshKeyFactory()
	{
		return new SshdSshKeyFactory();
	}

	@Bean
	public CredentialService credentialService(CredentialStore credentialStore, CredentialRefStore credentialRefStore, SshKeyFactory sshKeyFactory,
			Clock clock)
	{
		return new CredentialService(credentialStore, credentialRefStore, sshKeyFactory, clock);
	}
}

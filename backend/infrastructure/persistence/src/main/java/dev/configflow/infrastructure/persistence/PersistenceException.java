package dev.configflow.infrastructure.persistence;

import java.sql.SQLException;

/**
 * Unchecked wrapper for {@link SQLException} so the domain ports stay free of
 * checked JDBC exceptions.
 */
public class PersistenceException extends RuntimeException {

    public PersistenceException(String message, SQLException cause) {
        super(message, cause);
    }
}

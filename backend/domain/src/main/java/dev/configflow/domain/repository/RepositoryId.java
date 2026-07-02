package dev.configflow.domain.repository;

import java.util.Objects;
import java.util.UUID;

/**
 * Identity of a registered {@link Repository} (UUID based).
 *
 * <p>This is the application-level identity stored in SQLite; it is unrelated to any
 * VCS-native identifier.</p>
 */
public record RepositoryId(UUID value) {

    public RepositoryId {
        Objects.requireNonNull(value, "value must not be null");
    }

    /** Creates a new random identity. */
    public static RepositoryId newId() {
        return new RepositoryId(UUID.randomUUID());
    }

    /** Parses the canonical UUID string form. */
    public static RepositoryId of(String value) {
        return new RepositoryId(UUID.fromString(value));
    }

    /** Canonical string form used in APIs and persistence. */
    public String asString() {
        return value.toString();
    }
}

package dev.configflow.domain.operation;

import java.util.Objects;
import java.util.UUID;

/**
 * Identity of a long-running {@link Operation} (UUID based).
 */
public record OperationId(UUID value) {

    public OperationId {
        Objects.requireNonNull(value, "value must not be null");
    }

    /** Creates a new random identity. */
    public static OperationId newId() {
        return new OperationId(UUID.randomUUID());
    }

    /** Parses the canonical UUID string form. */
    public static OperationId of(String value) {
        return new OperationId(UUID.fromString(value));
    }

    /** Canonical string form used in APIs and persistence. */
    public String asString() {
        return value.toString();
    }
}

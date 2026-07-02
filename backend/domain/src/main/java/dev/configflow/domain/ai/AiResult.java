package dev.configflow.domain.ai;

import java.util.Objects;

/**
 * Result wrapper for AI calls.
 *
 * <p>M0 only carries a synchronous value; a streaming (token subscription) variant is
 * planned for when real providers land, without changing the port signatures.</p>
 *
 * @param value the produced value
 * @param <T>   value type
 */
public record AiResult<T>(T value) {

    public AiResult {
        Objects.requireNonNull(value, "value must not be null");
    }

    /** Wraps a synchronously produced value. */
    public static <T> AiResult<T> of(T value) {
        return new AiResult<>(value);
    }
}

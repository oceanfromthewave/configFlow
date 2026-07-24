package dev.configflow.api.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.configflow.domain.operation.Operation;
import dev.configflow.domain.operation.OperationFailure;
import dev.configflow.domain.operation.OperationFailures;
import dev.configflow.domain.operation.OperationId;
import dev.configflow.domain.operation.OperationState;
import dev.configflow.domain.operation.OperationType;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The 401 flow in docs/07 §4 starts here: an operation fails minutes after its request
 * answered, and this event is the only way the frontend hears about it.
 */
class SseOperationEventsTest {

    private final RecordingBroadcaster broadcaster = new RecordingBroadcaster();
    private final SseOperationEvents events = new SseOperationEvents(broadcaster);

    @Test
    @SuppressWarnings("unchecked")
    void aFailedOperationSaysWhoseCredentialsToAskFor() {
        events.completed(failed(new OperationFailure(
                OperationFailures.VCS_AUTH_REQUIRED,
                "Authentication required for https://github.com",
                Map.of("host", "github.com", "protocol", "https"))));

        Map<String, Object> payload = broadcaster.only("operation.completed");
        Map<String, Object> error = (Map<String, Object>) payload.get("error");

        assertEquals(OperationFailures.VCS_AUTH_REQUIRED, error.get("code"));
        assertEquals("Authentication required for https://github.com", error.get("detail"));
        assertEquals(Map.of("host", "github.com", "protocol", "https"), error.get("context"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void theCodeIsTheOperationsOwn() {
        // It used to be the literal OPERATION_FAILED for every failure, which left the
        // client unable to tell a rejected password from an unreachable host.
        events.completed(failed(
                OperationFailure.of(OperationFailures.VCS_NETWORK_ERROR, "unreachable")));

        Map<String, Object> error =
                (Map<String, Object>) broadcaster.only("operation.completed").get("error");

        assertEquals(OperationFailures.VCS_NETWORK_ERROR, error.get("code"));
        assertTrue(((Map<String, Object>) error.get("context")).isEmpty());
    }

    @Test
    void anOperationThatSucceededReportsNoError() {
        events.completed(new Operation(
                OperationId.newId(), null, OperationType.FETCH, OperationState.SUCCEEDED,
                null, Instant.parse("2026-07-02T10:00:00Z"),
                Instant.parse("2026-07-02T10:00:05Z"), null, List.of()));

        assertNull(broadcaster.only("operation.completed").get("error"));
    }

    private static Operation failed(OperationFailure failure) {
        return new Operation(
                OperationId.newId(), null, OperationType.FETCH, OperationState.FAILED,
                null, Instant.parse("2026-07-02T10:00:00Z"),
                Instant.parse("2026-07-02T10:00:05Z"), failure, List.of());
    }

    private static final class RecordingBroadcaster extends EventBroadcaster {

        private final List<String> names = new ArrayList<>();
        private final List<Object> payloads = new ArrayList<>();

        @Override
        public void broadcast(String eventName, Object data) {
            names.add(eventName);
            payloads.add(data);
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> only(String eventName) {
            assertEquals(List.of(eventName), names);
            return (Map<String, Object>) payloads.get(0);
        }
    }
}

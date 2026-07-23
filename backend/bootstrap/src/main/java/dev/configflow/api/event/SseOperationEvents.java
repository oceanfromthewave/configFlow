package dev.configflow.api.event;

import dev.configflow.domain.operation.Operation;
import dev.configflow.domain.operation.OperationEvents;
import dev.configflow.domain.operation.OperationId;
import dev.configflow.domain.operation.OperationProgress;
import dev.configflow.domain.repository.RepositoryId;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Publishes operation events onto the SSE stream (docs/07 §3).
 *
 * <p>This is the only place that knows both the domain event port and the transport.
 * Every method swallows its own failures: a browser that vanished mid-clone must not
 * make the clone fail.</p>
 */
@Component
public class SseOperationEvents implements OperationEvents {

    private static final Logger log = LoggerFactory.getLogger(SseOperationEvents.class);

    /** Placeholder until failures carry a domain code (arrives with the auth work). */
    private static final String GENERIC_FAILURE_CODE = "OPERATION_FAILED";

    private final EventBroadcaster broadcaster;

    public SseOperationEvents(EventBroadcaster broadcaster) {
        this.broadcaster = broadcaster;
    }

    @Override
    public void progress(OperationId id, OperationProgress progress) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("operationId", id.asString());
        payload.put("percent", progress.percent());
        payload.put("phase", progress.phase());
        payload.put("detail", progress.detail());
        send("operation.progress", payload);
    }

    @Override
    public void completed(Operation operation) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("operationId", operation.id().asString());
        payload.put("state", operation.state().name());
        payload.put("error", errorOf(operation));
        send("operation.completed", payload);
    }

    @Override
    public void consoleLine(
            RepositoryId repositoryId, OperationId id, String line, String level) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("repositoryId", repositoryId == null ? null : repositoryId.asString());
        payload.put("operationId", id.asString());
        payload.put("line", line);
        payload.put("level", level);
        send("console.line", payload);
    }

    @Override
    public void refsChanged(RepositoryId repositoryId) {
        sendRepositoryEvent("repository.refs-changed", repositoryId);
    }

    @Override
    public void workingTreeChanged(RepositoryId repositoryId) {
        sendRepositoryEvent("workingtree.changed", repositoryId);
    }

    @Override
    public void repositoryRegistered(RepositoryId repositoryId) {
        sendRepositoryEvent("repository.registered", repositoryId);
    }

    /**
     * Both events exist to invalidate one repository's caches, so without an id there is
     * nothing for a client to act on. Dropping it beats an NPE: the port promises these
     * never throw, and work with no repository — a clone into a new directory — is
     * exactly the case that passes null.
     */
    private void sendRepositoryEvent(String eventName, RepositoryId repositoryId) {
        if (repositoryId == null) {
            return;
        }
        send(eventName, Map.of("repositoryId", repositoryId.asString()));
    }

    /** Null unless the operation actually failed, which is what the contract promises. */
    private static Map<String, Object> errorOf(Operation operation) {
        if (operation.errorMessage() == null) {
            return null;
        }
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("code", GENERIC_FAILURE_CODE);
        error.put("detail", operation.errorMessage());
        return error;
    }

    private void send(String eventName, Object payload) {
        try {
            broadcaster.broadcast(eventName, payload);
        } catch (RuntimeException e) {
            log.warn("Failed to publish {}: {}", eventName, e.toString());
        }
    }
}

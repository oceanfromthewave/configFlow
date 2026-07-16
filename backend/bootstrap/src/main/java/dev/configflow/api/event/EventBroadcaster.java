package dev.configflow.api.event;

import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Fan-out hub for server-sent events.
 *
 * <p>The SSE endpoint registers each connected client here; application-layer code
 * publishes domain events ({@code operation.progress}, {@code workingtree.changed},
 * ...) through {@link #broadcast(String, Object)}. A {@code heartbeat} event every
 * 15 seconds keeps intermediaries from closing idle connections and lets the
 * frontend detect a dead backend quickly.</p>
 */
@Component
public class EventBroadcaster {

    private static final Logger log = LoggerFactory.getLogger(EventBroadcaster.class);
    private static final long HEARTBEAT_INTERVAL_MS = 15_000;

    private final Set<SseEmitter> emitters = ConcurrentHashMap.newKeySet();

    /** Registers a new SSE client; the emitter never times out on our side. */
    public SseEmitter register() {
        SseEmitter emitter = new SseEmitter(0L);
        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> emitters.remove(emitter));
        emitter.onError(e -> emitters.remove(emitter));
        emitters.add(emitter);
        return emitter;
    }

    /** Sends {@code data} as event {@code eventName} to every connected client. */
    public void broadcast(String eventName, Object data) {
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event().name(eventName).data(data));
            } catch (IOException | IllegalStateException e) {
                log.debug("Dropping dead SSE client: {}", e.getMessage());
                emitters.remove(emitter);
                emitter.completeWithError(e);
            }
        }
    }

    /** Number of currently connected clients (mainly for tests/diagnostics). */
    public int clientCount() {
        return emitters.size();
    }

    @Scheduled(fixedRate = HEARTBEAT_INTERVAL_MS)
    void heartbeat() {
        if (!emitters.isEmpty()) {
            broadcast("heartbeat", Map.of("timestamp", Instant.now().toString()));
        }
    }
}

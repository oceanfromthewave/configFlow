package dev.configflow.api.event;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * SSE stream the frontend subscribes to with {@code EventSource}.
 *
 * <p>Because {@code EventSource} cannot set request headers, clients authenticate
 * this endpoint with the {@code ?token=} query parameter (accepted by the token
 * filter for exactly this reason).</p>
 */
@RestController
@RequestMapping("/api/v1/events")
public class EventsController {

    private final EventBroadcaster broadcaster;

    public EventsController(EventBroadcaster broadcaster) {
        this.broadcaster = broadcaster;
    }

    @GetMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter subscribe() {
        return broadcaster.register();
    }
}

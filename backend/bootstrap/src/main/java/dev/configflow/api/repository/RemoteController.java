package dev.configflow.api.repository;

import dev.configflow.application.remote.RemoteService;
import dev.configflow.domain.operation.Operation;
import dev.configflow.domain.operation.OperationState;
import dev.configflow.domain.operation.OperationType;
import dev.configflow.domain.repository.RepositoryId;
import dev.configflow.domain.vcs.model.PullRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Remote synchronisation (docs/07 §2). Every endpoint answers {@code 202 Accepted}: these
 * take as long as the network does, so progress and completion arrive over SSE.
 */
@RestController
@RequestMapping("/api/v1/repositories")
public class RemoteController {

    /** The 202 body: enough for the client to follow the operation. */
    public record AcceptedResponse(String operationId, OperationType type, OperationState state) {

        static AcceptedResponse from(Operation operation) {
            return new AcceptedResponse(
                    operation.id().asString(), operation.type(), operation.state());
        }
    }

    /** POST body for fetch. All fields optional; the defaults match plain {@code git fetch}. */
    public record FetchBody(String remote, Boolean prune) {
    }

    /** POST body for pull. */
    public record PullBody(String remote, PullRequest.Strategy strategy) {
    }

    /** POST body for push. */
    public record PushBody(String remote, Boolean forceWithLease, Boolean tags) {
    }

    private final RemoteService remoteService;

    public RemoteController(RemoteService remoteService) {
        this.remoteService = remoteService;
    }

    @PostMapping("/{id}/fetch")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public AcceptedResponse fetch(
            @PathVariable String id, @RequestBody(required = false) FetchBody body) {
        return AcceptedResponse.from(remoteService.fetch(
                RepositoryId.of(id),
                body == null ? null : body.remote(),
                body != null && Boolean.TRUE.equals(body.prune())));
    }

    @PostMapping("/{id}/pull")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public AcceptedResponse pull(
            @PathVariable String id, @RequestBody(required = false) PullBody body) {
        return AcceptedResponse.from(remoteService.pull(
                RepositoryId.of(id),
                body == null ? null : body.remote(),
                body == null ? null : body.strategy()));
    }

    @PostMapping("/{id}/push")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public AcceptedResponse push(
            @PathVariable String id, @RequestBody(required = false) PushBody body) {
        return AcceptedResponse.from(remoteService.push(
                RepositoryId.of(id),
                body == null ? null : body.remote(),
                body != null && Boolean.TRUE.equals(body.forceWithLease()),
                body != null && Boolean.TRUE.equals(body.tags())));
    }
}

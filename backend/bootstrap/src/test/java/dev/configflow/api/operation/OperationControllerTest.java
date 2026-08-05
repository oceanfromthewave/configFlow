package dev.configflow.api.operation;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.configflow.application.operation.OperationQueue;
import dev.configflow.domain.operation.Operation;
import dev.configflow.domain.operation.OperationId;
import dev.configflow.domain.operation.OperationProgress;
import dev.configflow.domain.operation.OperationState;
import dev.configflow.domain.operation.OperationType;
import dev.configflow.domain.repository.RepositoryId;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(OperationController.class)
class OperationControllerTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private OperationQueue queue;

    private static Operation running(OperationId id) {
        return new Operation(
                id, RepositoryId.newId(), OperationType.CHECKOUT, OperationState.RUNNING,
                new OperationProgress(40, "Switching", "main"), NOW, null, null,
                List.of("checkout main"));
    }

    @Test
    void list_flattensOperationsForTheClient() throws Exception {
        OperationId id = OperationId.newId();
        when(queue.list(any(), any())).thenReturn(List.of(running(id)));

        mvc.perform(get("/api/v1/operations"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].operationId").value(id.asString()))
                .andExpect(jsonPath("$[0].type").value("CHECKOUT"))
                .andExpect(jsonPath("$[0].state").value("RUNNING"))
                .andExpect(jsonPath("$[0].progress.percent").value(40))
                .andExpect(jsonPath("$[0].logLines[0]").value("checkout main"));
    }

    @Test
    void list_passesTheFiltersThrough() throws Exception {
        when(queue.list(any(), any())).thenReturn(List.of());
        String repositoryId = UUID.randomUUID().toString();

        mvc.perform(get("/api/v1/operations")
                        .param("repositoryId", repositoryId)
                        .param("state", "RUNNING"))
                .andExpect(status().isOk());

        verify(queue).list(eq(RepositoryId.of(repositoryId)), eq(OperationState.RUNNING));
    }

    @Test
    void list_withoutFiltersAsksForEverything() throws Exception {
        when(queue.list(any(), any())).thenReturn(List.of());

        mvc.perform(get("/api/v1/operations")).andExpect(status().isOk());

        verify(queue).list(isNull(), isNull());
    }

    @Test
    void list_unknownStateIs400() throws Exception {
        mvc.perform(get("/api/v1/operations").param("state", "SLEEPING"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void show_returnsOneOperation() throws Exception {
        OperationId id = OperationId.newId();
        when(queue.find(any())).thenReturn(Optional.of(running(id)));

        mvc.perform(get("/api/v1/operations/" + id.asString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.operationId").value(id.asString()));
    }

    @Test
    void show_unknownIdIs404() throws Exception {
        when(queue.find(any())).thenReturn(Optional.empty());

        mvc.perform(get("/api/v1/operations/" + UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void show_malformedIdIs400() throws Exception {
        mvc.perform(get("/api/v1/operations/not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void cancel_acceptedRequestAnswers204() throws Exception {
        OperationId id = OperationId.newId();
        when(queue.cancel(any())).thenReturn(true);

        mvc.perform(post("/api/v1/operations/" + id.asString() + "/cancel"))
                .andExpect(status().isNoContent());

        verify(queue).cancel(id);
    }

    @Test
    void cancel_alreadyFinishedOrUnknownIs404() throws Exception {
        when(queue.cancel(any())).thenReturn(false);

        mvc.perform(post("/api/v1/operations/" + UUID.randomUUID() + "/cancel"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void retry_acceptedRequestAnswers202WithTheFreshOperation() throws Exception {
        OperationId original = OperationId.newId();
        OperationId fresh = OperationId.newId();
        // Retry mints a new operation, so the client is answered with that one to follow.
        when(queue.retry(original)).thenReturn(Optional.of(new Operation(
                fresh, RepositoryId.newId(), OperationType.FETCH, OperationState.QUEUED,
                null, null, null, null, List.of())));

        mvc.perform(post("/api/v1/operations/" + original.asString() + "/retry"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.operationId").value(fresh.asString()))
                .andExpect(jsonPath("$.state").value("QUEUED"));

        verify(queue).retry(original);
    }

    @Test
    void retry_nothingRetryableIs404() throws Exception {
        when(queue.retry(any())).thenReturn(Optional.empty());

        mvc.perform(post("/api/v1/operations/" + UUID.randomUUID() + "/retry"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }
}

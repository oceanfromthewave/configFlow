package dev.configflow.api.stash;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.configflow.application.stash.StashService;
import dev.configflow.domain.operation.Operation;
import dev.configflow.domain.operation.OperationType;
import dev.configflow.domain.repository.RepositoryId;
import dev.configflow.domain.vcs.model.StashEntry;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(StashController.class)
class StashControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private StashService stashService;

    private static Operation queued(OperationType type) {
        return Operation.queued(RepositoryId.newId(), type);
    }

    @Test
    void list_returnsStashEntries() throws Exception {
        String id = UUID.randomUUID().toString();
        when(stashService.list(eq(RepositoryId.of(id))))
                .thenReturn(List.of(new StashEntry(0, "WIP on main", Instant.EPOCH)));

        mvc.perform(get("/api/v1/repositories/{id}/stashes", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].index").value(0))
                .andExpect(jsonPath("$[0].message").value("WIP on main"));
    }

    @Test
    void save_answers202Accepted() throws Exception {
        String id = UUID.randomUUID().toString();
        Operation op = queued(OperationType.STASH);
        when(stashService.save(any(), any(), anyBoolean())).thenReturn(op);

        mvc.perform(post("/api/v1/repositories/{id}/stashes", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"my stash\",\"includeUntracked\":true}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.operationId").value(op.id().asString()))
                .andExpect(jsonPath("$.type").value("STASH"));

        verify(stashService).save(eq(RepositoryId.of(id)), eq("my stash"), eq(true));
    }

    @Test
    void apply_answers202Accepted() throws Exception {
        String id = UUID.randomUUID().toString();
        Operation op = queued(OperationType.STASH);
        when(stashService.apply(any(), anyInt())).thenReturn(op);

        mvc.perform(post("/api/v1/repositories/{id}/stashes/0/apply", id))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.operationId").value(op.id().asString()));

        verify(stashService).apply(eq(RepositoryId.of(id)), eq(0));
    }

    @Test
    void pop_answers202Accepted() throws Exception {
        String id = UUID.randomUUID().toString();
        Operation op = queued(OperationType.STASH);
        when(stashService.pop(any(), anyInt())).thenReturn(op);

        mvc.perform(post("/api/v1/repositories/{id}/stashes/0/pop", id))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.operationId").value(op.id().asString()));

        verify(stashService).pop(eq(RepositoryId.of(id)), eq(0));
    }

    @Test
    void drop_answers202Accepted() throws Exception {
        String id = UUID.randomUUID().toString();
        Operation op = queued(OperationType.STASH);
        when(stashService.drop(any(), anyInt())).thenReturn(op);

        mvc.perform(delete("/api/v1/repositories/{id}/stashes/0", id))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.operationId").value(op.id().asString()));

        verify(stashService).drop(eq(RepositoryId.of(id)), eq(0));
    }
}

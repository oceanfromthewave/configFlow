package dev.configflow.api.rebase;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.configflow.application.rebase.RebaseService;
import dev.configflow.domain.operation.Operation;
import dev.configflow.domain.operation.OperationType;
import dev.configflow.domain.repository.RepositoryId;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(RebaseController.class)
class RebaseControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private RebaseService rebaseService;

    private static Operation queued() {
        return Operation.queued(RepositoryId.newId(), OperationType.REBASE);
    }

    @Test
    void start_answers202Accepted() throws Exception {
        String id = UUID.randomUUID().toString();
        Operation op = queued();
        when(rebaseService.start(any(), any())).thenReturn(op);

        mvc.perform(post("/api/v1/repositories/{id}/rebase", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"upstream\":\"main\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.operationId").value(op.id().asString()))
                .andExpect(jsonPath("$.type").value("REBASE"));

        verify(rebaseService).start(eq(RepositoryId.of(id)), eq("main"));
    }

    @Test
    void start_withoutBodyAnswers400() throws Exception {
        String id = UUID.randomUUID().toString();

        mvc.perform(post("/api/v1/repositories/{id}/rebase", id))
                .andExpect(status().isBadRequest());
    }

    @Test
    void continueRebase_answers202Accepted() throws Exception {
        String id = UUID.randomUUID().toString();
        Operation op = queued();
        when(rebaseService.continueRebase(any())).thenReturn(op);

        mvc.perform(post("/api/v1/repositories/{id}/rebase/continue", id))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.operationId").value(op.id().asString()));

        verify(rebaseService).continueRebase(eq(RepositoryId.of(id)));
    }

    @Test
    void abort_answers202Accepted() throws Exception {
        String id = UUID.randomUUID().toString();
        when(rebaseService.abort(any())).thenReturn(queued());

        mvc.perform(post("/api/v1/repositories/{id}/rebase/abort", id))
                .andExpect(status().isAccepted());

        verify(rebaseService).abort(eq(RepositoryId.of(id)));
    }

    @Test
    void skip_answers202Accepted() throws Exception {
        String id = UUID.randomUUID().toString();
        when(rebaseService.skip(any())).thenReturn(queued());

        mvc.perform(post("/api/v1/repositories/{id}/rebase/skip", id))
                .andExpect(status().isAccepted());

        verify(rebaseService).skip(eq(RepositoryId.of(id)));
    }
}

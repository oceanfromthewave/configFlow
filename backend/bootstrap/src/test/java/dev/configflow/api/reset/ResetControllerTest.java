package dev.configflow.api.reset;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.configflow.application.reset.ResetService;
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

@WebMvcTest(ResetController.class)
class ResetControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private ResetService resetService;

    private static Operation queued() {
        return Operation.queued(RepositoryId.newId(), OperationType.RESET);
    }

    @Test
    void reset_answers202Accepted() throws Exception {
        String id = UUID.randomUUID().toString();
        Operation op = queued();
        when(resetService.reset(any(), any(), any())).thenReturn(op);

        mvc.perform(post("/api/v1/repositories/{id}/reset", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"target\":\"abc123\",\"mode\":\"hard\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.operationId").value(op.id().asString()))
                .andExpect(jsonPath("$.type").value("RESET"));

        verify(resetService).reset(eq(RepositoryId.of(id)), eq("abc123"), eq("hard"));
    }

    @Test
    void reset_withoutBodyAnswers400() throws Exception {
        String id = UUID.randomUUID().toString();

        mvc.perform(post("/api/v1/repositories/{id}/reset", id))
                .andExpect(status().isBadRequest());
    }
}

package dev.configflow.api.revert;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.configflow.application.revert.RevertService;
import dev.configflow.domain.operation.Operation;
import dev.configflow.domain.operation.OperationType;
import dev.configflow.domain.repository.RepositoryId;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(RevertController.class)
class RevertControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private RevertService revertService;

    private static Operation queued() {
        return Operation.queued(RepositoryId.newId(), OperationType.REVERT);
    }

    @Test
    void revert_answers202Accepted() throws Exception {
        String id = UUID.randomUUID().toString();
        Operation op = queued();
        when(revertService.revert(any(), any())).thenReturn(op);

        mvc.perform(post("/api/v1/repositories/{id}/revert", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"revisions\":[\"abc123\",\"def456\"]}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.operationId").value(op.id().asString()))
                .andExpect(jsonPath("$.type").value("REVERT"));

        verify(revertService)
                .revert(eq(RepositoryId.of(id)), eq(List.of("abc123", "def456")));
    }

    @Test
    void revert_withoutBodyAnswers400() throws Exception {
        String id = UUID.randomUUID().toString();

        mvc.perform(post("/api/v1/repositories/{id}/revert", id))
                .andExpect(status().isBadRequest());
    }
}

package dev.configflow.api.cherrypick;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.configflow.application.cherrypick.CherryPickService;
import dev.configflow.domain.operation.Operation;
import dev.configflow.domain.operation.OperationType;
import dev.configflow.domain.repository.RepositoryId;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(CherryPickController.class)
class CherryPickControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private CherryPickService cherryPickService;

    private static Operation queued() {
        return Operation.queued(RepositoryId.newId(), OperationType.CHERRY_PICK);
    }

    @Test
    void cherryPick_answers202Accepted() throws Exception {
        String id = UUID.randomUUID().toString();
        Operation op = queued();
        when(cherryPickService.cherryPick(any(), any())).thenReturn(op);

        mvc.perform(post("/api/v1/repositories/{id}/cherry-pick", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"revisions\":[\"abc123\",\"def456\"]}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.operationId").value(op.id().asString()))
                .andExpect(jsonPath("$.type").value("CHERRY_PICK"));

        verify(cherryPickService)
                .cherryPick(eq(RepositoryId.of(id)), eq(List.of("abc123", "def456")));
    }

    @Test
    void cherryPick_withoutBodyAnswers400() throws Exception {
        String id = UUID.randomUUID().toString();

        mvc.perform(post("/api/v1/repositories/{id}/cherry-pick", id))
                .andExpect(status().isBadRequest());
    }
}

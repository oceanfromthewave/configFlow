package dev.configflow.api.tag;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.configflow.application.tag.TagService;
import dev.configflow.domain.operation.Operation;
import dev.configflow.domain.operation.OperationType;
import dev.configflow.domain.repository.RepositoryId;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(TagController.class)
class TagControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private TagService tagService;

    private static Operation queued(OperationType type) {
        return Operation.queued(RepositoryId.newId(), type);
    }

    @Test
    void create_answers202Accepted() throws Exception {
        String id = UUID.randomUUID().toString();
        Operation op = queued(OperationType.TAG);
        when(tagService.create(any(), any(), any(), any())).thenReturn(op);

        mvc.perform(post("/api/v1/repositories/{id}/tags", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"v1.0\",\"target\":\"abc123\",\"message\":\"release\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.operationId").value(op.id().asString()))
                .andExpect(jsonPath("$.type").value("TAG"));

        verify(tagService).create(eq(RepositoryId.of(id)), eq("v1.0"), eq("abc123"), eq("release"));
    }

    @Test
    void create_withoutBodyAnswers400() throws Exception {
        String id = UUID.randomUUID().toString();

        mvc.perform(post("/api/v1/repositories/{id}/tags", id))
                .andExpect(status().isBadRequest());
    }

    @Test
    void delete_answers202Accepted() throws Exception {
        String id = UUID.randomUUID().toString();
        Operation op = queued(OperationType.TAG);
        when(tagService.delete(any(), any())).thenReturn(op);

        mvc.perform(delete("/api/v1/repositories/{id}/tags/v1.0", id))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.operationId").value(op.id().asString()));

        verify(tagService).delete(eq(RepositoryId.of(id)), eq("v1.0"));
    }

    @Test
    void delete_withSlashInNameStripsLeadingSlash() throws Exception {
        String id = UUID.randomUUID().toString();
        Operation op = queued(OperationType.TAG);
        when(tagService.delete(any(), any())).thenReturn(op);

        mvc.perform(delete("/api/v1/repositories/{id}/tags/release/1.0", id))
                .andExpect(status().isAccepted());

        verify(tagService).delete(eq(RepositoryId.of(id)), eq("release/1.0"));
    }
}

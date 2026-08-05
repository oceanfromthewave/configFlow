package dev.configflow.api.repository;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.configflow.application.repository.RepositoryService;
import dev.configflow.domain.repository.RepositoryId;
import dev.configflow.domain.vcs.model.ConflictedFile;
import dev.configflow.domain.vcs.model.ThreeWayContent;
import java.nio.file.Path;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(ConflictController.class)
class ConflictControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private RepositoryService repositoryService;

    @Test
    void list_returnsTheConflictedFiles() throws Exception {
        String id = UUID.randomUUID().toString();
        when(repositoryService.listConflicts(RepositoryId.of(id)))
                .thenReturn(List.of(ConflictedFile.unresolved(Path.of("base.txt"))));

        mvc.perform(get("/api/v1/repositories/{id}/conflicts", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].path").value("base.txt"))
                .andExpect(jsonPath("$[0].resolution").value("UNRESOLVED"));
    }

    @Test
    void content_returnsTheThreeSides() throws Exception {
        String id = UUID.randomUUID().toString();
        when(repositoryService.threeWayContent(eq(RepositoryId.of(id)), eq(Path.of("base.txt"))))
                .thenReturn(new ThreeWayContent("base\n", "mine\n", "theirs\n"));

        mvc.perform(get("/api/v1/repositories/{id}/conflicts/content", id).param("path", "base.txt"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.base").value("base\n"))
                .andExpect(jsonPath("$.mine").value("mine\n"))
                .andExpect(jsonPath("$.theirs").value("theirs\n"));
    }

    @Test
    void content_withoutPathAnswers400() throws Exception {
        String id = UUID.randomUUID().toString();

        mvc.perform(get("/api/v1/repositories/{id}/conflicts/content", id))
                .andExpect(status().isBadRequest());
    }

    @Test
    void content_forAnUnconflictedPathAnswers404() throws Exception {
        String id = UUID.randomUUID().toString();
        when(repositoryService.threeWayContent(any(), any())).thenThrow(new NoSuchElementException("not conflicted"));

        mvc.perform(get("/api/v1/repositories/{id}/conflicts/content", id).param("path", "clean.txt"))
                .andExpect(status().isNotFound());
    }

    @Test
    void resolve_answers204NoContent() throws Exception {
        String id = UUID.randomUUID().toString();

        mvc.perform(post("/api/v1/repositories/{id}/conflicts/resolve", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"path\":\"base.txt\",\"resolution\":\"MINE\"}"))
                .andExpect(status().isNoContent());

        verify(repositoryService).resolve(RepositoryId.of(id), Path.of("base.txt"), ConflictedFile.Resolution.MINE, null);
    }

    @Test
    void resolve_withManualContentPassesItThrough() throws Exception {
        String id = UUID.randomUUID().toString();

        mvc.perform(post("/api/v1/repositories/{id}/conflicts/resolve", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"path\":\"base.txt\",\"resolution\":\"MANUAL\",\"content\":\"merged\\n\"}"))
                .andExpect(status().isNoContent());

        verify(repositoryService).resolve(RepositoryId.of(id), Path.of("base.txt"), ConflictedFile.Resolution.MANUAL, "merged\n");
    }

    @Test
    void resolve_withoutBodyAnswers400() throws Exception {
        String id = UUID.randomUUID().toString();

        mvc.perform(post("/api/v1/repositories/{id}/conflicts/resolve", id))
                .andExpect(status().isBadRequest());
    }

    @Test
    void resolve_translatesIllegalArgumentTo400() throws Exception {
        String id = UUID.randomUUID().toString();
        doThrow(new IllegalArgumentException("bad resolution")).when(repositoryService)
                .resolve(any(), any(), any(), any());

        mvc.perform(post("/api/v1/repositories/{id}/conflicts/resolve", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"path\":\"base.txt\",\"resolution\":\"UNRESOLVED\"}"))
                .andExpect(status().isBadRequest());
    }
}

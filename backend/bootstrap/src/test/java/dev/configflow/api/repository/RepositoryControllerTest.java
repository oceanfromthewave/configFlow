package dev.configflow.api.repository;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.configflow.application.repository.RepositoryService;
import dev.configflow.domain.repository.Repository;
import dev.configflow.domain.vcs.model.ChangeType;
import dev.configflow.domain.vcs.model.FileChange;
import dev.configflow.domain.vcs.model.VcsType;
import dev.configflow.domain.vcs.model.WorkingTreeStatus;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Web-slice test of the repository controller: verifies HTTP mapping, DTO shape and the
 * exception-to-Problem-Details translation, with the application service mocked. Service
 * logic is covered by RepositoryServiceTest and the full stack by manual runtime checks.
 */
@WebMvcTest(RepositoryController.class)
class RepositoryControllerTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private RepositoryService repositoryService;

    private static Repository sample() {
        return Repository.register("demo", Path.of("C:/dev/demo"), null, VcsType.GIT, NOW);
    }

    @Test
    void list_returnsRepositoriesAsJson() throws Exception {
        when(repositoryService.list()).thenReturn(List.of(sample()));

        mvc.perform(get("/api/v1/repositories"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("demo"))
                .andExpect(jsonPath("$[0].vcsType").value("GIT"))
                .andExpect(jsonPath("$[0].localPath").exists());
    }

    @Test
    void register_returnsRegisteredRepository() throws Exception {
        when(repositoryService.register(any())).thenReturn(sample());

        mvc.perform(post("/api/v1/repositories")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"localPath\":\"C:/dev/demo\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("demo"))
                .andExpect(jsonPath("$.vcsType").value("GIT"));
    }

    @Test
    void register_missingLocalPathIs400() throws Exception {
        mvc.perform(post("/api/v1/repositories")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void register_unsupportedPathIs400() throws Exception {
        when(repositoryService.register(any()))
                .thenThrow(new IllegalArgumentException("Not a supported repository"));

        mvc.perform(post("/api/v1/repositories")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"localPath\":\"C:/tmp/plain\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void open_unknownIdIs404() throws Exception {
        when(repositoryService.open(any()))
                .thenThrow(new NoSuchElementException("Repository not found"));

        mvc.perform(post("/api/v1/repositories/" + UUID.randomUUID() + "/open"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void open_malformedIdIs400() throws Exception {
        mvc.perform(post("/api/v1/repositories/not-a-uuid/open"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void status_returnsBucketsAsJson() throws Exception {
        WorkingTreeStatus status = new WorkingTreeStatus(
                List.of(FileChange.of(Path.of("staged.txt"), ChangeType.ADDED)),
                List.of(FileChange.of(Path.of("changed.txt"), ChangeType.MODIFIED)),
                List.of());
        when(repositoryService.status(any())).thenReturn(status);

        mvc.perform(get("/api/v1/repositories/" + UUID.randomUUID() + "/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.staged[0].path").value("staged.txt"))
                .andExpect(jsonPath("$.staged[0].type").value("ADDED"))
                .andExpect(jsonPath("$.unstaged[0].type").value("MODIFIED"))
                .andExpect(jsonPath("$.conflicted").isEmpty());
    }
}

package dev.configflow.api.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.configflow.application.repository.RepositoryService;
import dev.configflow.domain.repository.Repository;
import dev.configflow.domain.repository.RepositoryId;
import dev.configflow.domain.vcs.model.ChangeType;
import dev.configflow.domain.vcs.model.CommitRequest;
import dev.configflow.domain.vcs.model.FileChange;
import dev.configflow.domain.vcs.model.RevisionId;
import dev.configflow.domain.vcs.model.VcsType;
import dev.configflow.domain.vcs.model.WorkingTreeStatus;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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

    @Test
    void stage_returns204AndForwardsThePaths() throws Exception {
        String id = UUID.randomUUID().toString();

        mvc.perform(post("/api/v1/repositories/" + id + "/stage")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"paths\":[\"a.txt\",\"src/b.txt\"]}"))
                .andExpect(status().isNoContent());

        verify(repositoryService).stage(
                eq(RepositoryId.of(id)), eq(List.of(Path.of("a.txt"), Path.of("src/b.txt"))));
    }

    @Test
    void unstage_returns204AndForwardsThePaths() throws Exception {
        String id = UUID.randomUUID().toString();

        mvc.perform(post("/api/v1/repositories/" + id + "/unstage")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"paths\":[\"a.txt\"]}"))
                .andExpect(status().isNoContent());

        verify(repositoryService).unstage(
                eq(RepositoryId.of(id)), eq(List.of(Path.of("a.txt"))));
    }

    @Test
    void stage_emptySelectionIs400() throws Exception {
        mvc.perform(post("/api/v1/repositories/" + UUID.randomUUID() + "/stage")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"paths\":[]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        verify(repositoryService, never()).stage(any(), any());
    }

    @Test
    void stage_escapingPathIs400() throws Exception {
        // stage() is void, so the failure has to be stubbed the other way round.
        doThrow(new IllegalArgumentException("Path must be inside the working copy"))
                .when(repositoryService).stage(any(), any());

        mvc.perform(post("/api/v1/repositories/" + UUID.randomUUID() + "/stage")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"paths\":[\"../outside.txt\"]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void commit_returnsTheCreatedRevisionId() throws Exception {
        when(repositoryService.commit(any(), any()))
                .thenReturn(new RevisionId("0123456789abcdef"));

        mvc.perform(post("/api/v1/repositories/" + UUID.randomUUID() + "/commit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"feat: add a thing\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revisionId").value("0123456789abcdef"));

        ArgumentCaptor<CommitRequest> captor = ArgumentCaptor.forClass(CommitRequest.class);
        verify(repositoryService).commit(any(), captor.capture());
        assertEquals("feat: add a thing", captor.getValue().message());
        // `amend` is absent from the body, so it must default to a plain commit.
        assertEquals(false, captor.getValue().amend());
    }

    @Test
    void commit_missingMessageIs400() throws Exception {
        mvc.perform(post("/api/v1/repositories/" + UUID.randomUUID() + "/commit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        verify(repositoryService, never()).commit(any(), any());
    }

    @Test
    void commit_unsupportedAmendIsCapabilityError() throws Exception {
        when(repositoryService.commit(any(), any()))
                .thenThrow(new UnsupportedOperationException("SVN does not support amending"));

        mvc.perform(post("/api/v1/repositories/" + UUID.randomUUID() + "/commit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"reword\",\"amend\":true}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CAPABILITY_NOT_SUPPORTED"));
    }
}

package dev.configflow.api.svn;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.configflow.application.svn.SvnService;
import dev.configflow.domain.operation.Operation;
import dev.configflow.domain.operation.OperationType;
import dev.configflow.domain.repository.RepositoryId;
import dev.configflow.domain.vcs.model.RemoteEntry;
import dev.configflow.domain.vcs.model.RevisionId;
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

@WebMvcTest(SvnController.class)
class SvnControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private SvnService svnService;

    private static Operation queued(OperationType type) {
        return Operation.queued(RepositoryId.newId(), type);
    }

    // --- lock / unlock -----------------------------------------------------

    @Test
    void lock_answers202AndForwardsPathsAndComment() throws Exception {
        when(svnService.lock(any(), any(), any())).thenReturn(queued(OperationType.LOCK));
        String id = UUID.randomUUID().toString();

        mvc.perform(post("/api/v1/repositories/" + id + "/locks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"paths\":[\"a.txt\",\"dir/b.txt\"],\"comment\":\"reserving\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.type").value("LOCK"));

        verify(svnService).lock(eq(RepositoryId.of(id)),
                eq(List.of(Path.of("a.txt"), Path.of("dir/b.txt"))), eq("reserving"));
    }

    @Test
    void lock_withoutABodyIs400() throws Exception {
        mvc.perform(post("/api/v1/repositories/" + UUID.randomUUID() + "/locks")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void lock_rejectsAnAbsolutePath() throws Exception {
        mvc.perform(post("/api/v1/repositories/" + UUID.randomUUID() + "/locks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"paths\":[\"C:/etc/passwd\"]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void lock_rejectsAPathEscapingTheWorkingCopy() throws Exception {
        mvc.perform(post("/api/v1/repositories/" + UUID.randomUUID() + "/locks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"paths\":[\"../outside.txt\"]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void unlock_answers202AndForwardsBreakLock() throws Exception {
        when(svnService.unlock(any(), any(), anyBoolean())).thenReturn(queued(OperationType.UNLOCK));
        String id = UUID.randomUUID().toString();

        mvc.perform(delete("/api/v1/repositories/" + id + "/locks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"paths\":[\"a.txt\"],\"breakLock\":true}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.type").value("UNLOCK"));

        verify(svnService).unlock(eq(RepositoryId.of(id)), eq(List.of(Path.of("a.txt"))), eq(true));
    }

    @Test
    void unlock_omittedBreakLockDefaultsToFalse() throws Exception {
        when(svnService.unlock(any(), any(), anyBoolean())).thenReturn(queued(OperationType.UNLOCK));

        mvc.perform(delete("/api/v1/repositories/" + UUID.randomUUID() + "/locks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"paths\":[\"a.txt\"]}"))
                .andExpect(status().isAccepted());

        verify(svnService).unlock(any(), any(), eq(false));
    }

    @Test
    void lock_onAProviderWithoutLockSupportIsCapabilityError() throws Exception {
        when(svnService.lock(any(), any(), any()))
                .thenThrow(new UnsupportedOperationException("GIT has no LockOperations"));

        mvc.perform(post("/api/v1/repositories/" + UUID.randomUUID() + "/locks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"paths\":[\"a.txt\"]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CAPABILITY_NOT_SUPPORTED"));
    }

    @Test
    void lock_unknownRepositoryIs404() throws Exception {
        when(svnService.lock(any(), any(), any()))
                .thenThrow(new NoSuchElementException("Repository not found"));

        mvc.perform(post("/api/v1/repositories/" + UUID.randomUUID() + "/locks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"paths\":[\"a.txt\"]}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    // --- browse --------------------------------------------------------

    @Test
    void browse_returnsTheRemoteEntries() throws Exception {
        String id = UUID.randomUUID().toString();
        when(svnService.browse(any(), any(), any())).thenReturn(List.of(
                new RemoteEntry("trunk", true, 0, new RevisionId("r5")),
                new RemoteEntry("README.md", false, 42, new RevisionId("r3"))));

        mvc.perform(get("/api/v1/repositories/" + id + "/svn/browse")
                        .param("url", "file:///repo")
                        .param("revision", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("trunk"))
                .andExpect(jsonPath("$[0].directory").value(true))
                .andExpect(jsonPath("$[1].name").value("README.md"))
                .andExpect(jsonPath("$[1].size").value(42));

        verify(svnService).browse(eq(RepositoryId.of(id)), eq("file:///repo"), eq("5"));
    }

    @Test
    void browse_withoutARevisionParamPassesNull() throws Exception {
        when(svnService.browse(any(), any(), any())).thenReturn(List.of());

        mvc.perform(get("/api/v1/repositories/" + UUID.randomUUID() + "/svn/browse")
                        .param("url", "file:///repo"))
                .andExpect(status().isOk());

        verify(svnService).browse(any(), eq("file:///repo"), isNull());
    }

    @Test
    void browse_withoutAUrlParamIs400() throws Exception {
        when(svnService.browse(any(), isNull(), any()))
                .thenThrow(new IllegalArgumentException("A 'url' is required"));

        mvc.perform(get("/api/v1/repositories/" + UUID.randomUUID() + "/svn/browse"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }
}

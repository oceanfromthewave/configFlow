package dev.configflow.api.repository;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.configflow.application.remote.CloneService;
import dev.configflow.application.remote.RemoteService;
import dev.configflow.domain.operation.Operation;
import dev.configflow.domain.operation.OperationType;
import dev.configflow.domain.repository.RepositoryId;
import dev.configflow.domain.vcs.exception.VcsAuthenticationRequiredException;
import dev.configflow.domain.vcs.exception.VcsNetworkException;
import dev.configflow.domain.vcs.exception.VcsPreconditionException;
import dev.configflow.domain.vcs.model.PullRequest;
import java.nio.file.Path;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(RemoteController.class)
class RemoteControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private RemoteService remoteService;

    @MockitoBean
    private CloneService cloneService;

    private static Operation queued(OperationType type) {
        return Operation.queued(RepositoryId.newId(), type);
    }

    @Test
    void fetch_answers202WithTheQueuedOperation() throws Exception {
        Operation operation = queued(OperationType.FETCH);
        when(remoteService.fetch(any(), any(), anyBoolean())).thenReturn(operation);
        String id = UUID.randomUUID().toString();

        mvc.perform(post("/api/v1/repositories/" + id + "/fetch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"remote\":\"origin\",\"prune\":true}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.operationId").value(operation.id().asString()))
                .andExpect(jsonPath("$.type").value("FETCH"))
                .andExpect(jsonPath("$.state").value("QUEUED"));

        verify(remoteService).fetch(eq(RepositoryId.of(id)), eq("origin"), eq(true));
    }

    @Test
    void fetch_withoutABodyUsesTheDefaults() throws Exception {
        when(remoteService.fetch(any(), any(), anyBoolean())).thenReturn(queued(OperationType.FETCH));

        mvc.perform(post("/api/v1/repositories/" + UUID.randomUUID() + "/fetch"))
                .andExpect(status().isAccepted());

        verify(remoteService).fetch(any(), isNull(), eq(false));
    }

    @Test
    void pull_forwardsTheStrategy() throws Exception {
        when(remoteService.pull(any(), any(), any())).thenReturn(queued(OperationType.PULL));

        mvc.perform(post("/api/v1/repositories/" + UUID.randomUUID() + "/pull")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"strategy\":\"REBASE\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.type").value("PULL"));

        verify(remoteService).pull(any(), isNull(), eq(PullRequest.Strategy.REBASE));
    }

    @Test
    void pull_withoutAStrategyLetsTheServiceDecide() throws Exception {
        when(remoteService.pull(any(), any(), any())).thenReturn(queued(OperationType.PULL));

        mvc.perform(post("/api/v1/repositories/" + UUID.randomUUID() + "/pull")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isAccepted());

        verify(remoteService).pull(any(), isNull(), isNull());
    }

    @Test
    void pull_unknownStrategyIs400() throws Exception {
        mvc.perform(post("/api/v1/repositories/" + UUID.randomUUID() + "/pull")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"strategy\":\"TELEPORT\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void push_forwardsEveryFlag() throws Exception {
        when(remoteService.push(any(), any(), anyBoolean(), anyBoolean()))
                .thenReturn(queued(OperationType.PUSH));

        mvc.perform(post("/api/v1/repositories/" + UUID.randomUUID() + "/push")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"remote\":\"upstream\",\"forceWithLease\":true,\"tags\":true}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.type").value("PUSH"));

        verify(remoteService).push(any(), eq("upstream"), eq(true), eq(true));
    }

    @Test
    void push_omittedFlagsDefaultToFalse() throws Exception {
        when(remoteService.push(any(), any(), anyBoolean(), anyBoolean()))
                .thenReturn(queued(OperationType.PUSH));

        mvc.perform(post("/api/v1/repositories/" + UUID.randomUUID() + "/push")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isAccepted());

        verify(remoteService).push(any(), isNull(), eq(false), eq(false));
    }

    @Test
    void push_rejectedByTheRemoteIs409() throws Exception {
        when(remoteService.push(any(), any(), anyBoolean(), anyBoolean()))
                .thenThrow(new VcsPreconditionException(
                        "The remote rejected this push — refs/heads/main: REJECTED_NONFASTFORWARD"));

        mvc.perform(post("/api/v1/repositories/" + UUID.randomUUID() + "/push")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));
    }

    @Test
    void fetch_authFailureIs401WithTheHost() throws Exception {
        when(remoteService.fetch(any(), any(), anyBoolean()))
                .thenThrow(new VcsAuthenticationRequiredException("github.com", "https", null));

        mvc.perform(post("/api/v1/repositories/" + UUID.randomUUID() + "/fetch"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("VCS_AUTH_REQUIRED"))
                .andExpect(jsonPath("$.context.host").value("github.com"));
    }

    @Test
    void fetch_unreachableRemoteIs502() throws Exception {
        when(remoteService.fetch(any(), any(), anyBoolean()))
                .thenThrow(new VcsNetworkException("Could not reach origin to fetch", null));

        mvc.perform(post("/api/v1/repositories/" + UUID.randomUUID() + "/fetch"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("VCS_NETWORK_ERROR"));
    }

    @Test
    void pull_unknownRepositoryIs404() throws Exception {
        when(remoteService.pull(any(), any(), any()))
                .thenThrow(new NoSuchElementException("Repository not found"));

        mvc.perform(post("/api/v1/repositories/" + UUID.randomUUID() + "/pull")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void push_onAProviderWithoutRemotesIsCapabilityError() throws Exception {
        when(remoteService.push(any(), any(), anyBoolean(), anyBoolean()))
                .thenThrow(new UnsupportedOperationException("SVN has no RemoteSyncOperations"));

        mvc.perform(post("/api/v1/repositories/" + UUID.randomUUID() + "/push")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CAPABILITY_NOT_SUPPORTED"));
    }

    @Test
    void clone_answers202AndForwardsTheTarget() throws Exception {
        when(cloneService.clone(any(), any(), any(), any()))
                .thenReturn(Operation.queued(null, OperationType.CLONE));

        mvc.perform(post("/api/v1/repositories/clone")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"https://host/o/r.git\",\"localPath\":\"C:/dev/r\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.type").value("CLONE"))
                .andExpect(jsonPath("$.state").value("QUEUED"));

        verify(cloneService).clone(
                eq("https://host/o/r.git"), eq(Path.of("C:/dev/r")), isNull(), isNull());
    }

    @Test
    void clone_withoutABodyIs400() throws Exception {
        mvc.perform(post("/api/v1/repositories/clone")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        verify(cloneService, never()).clone(any(), any(), any(), any());
    }

    @Test
    void clone_intoAnOccupiedDirectoryIs400() throws Exception {
        when(cloneService.clone(any(), any(), any(), any()))
                .thenThrow(new IllegalArgumentException("The target directory is not empty"));

        mvc.perform(post("/api/v1/repositories/clone")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"https://host/o/r.git\",\"localPath\":\"C:/dev/r\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }
}

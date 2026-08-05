package dev.configflow.api.repository;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.configflow.application.branch.BranchService;
import dev.configflow.domain.operation.Operation;
import dev.configflow.domain.operation.OperationType;
import dev.configflow.domain.repository.RepositoryId;
import dev.configflow.domain.vcs.exception.VcsPreconditionException;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(BranchController.class)
class BranchControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private BranchService branchService;

    private static Operation queued(OperationType type) {
        return Operation.queued(RepositoryId.newId(), type);
    }

    @Test
    void checkout_answers202WithTheQueuedOperation() throws Exception {
        Operation operation = queued(OperationType.CHECKOUT);
        when(branchService.checkout(any(), any())).thenReturn(operation);
        String id = UUID.randomUUID().toString();

        mvc.perform(post("/api/v1/repositories/" + id + "/checkout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ref\":\"feature/x\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.operationId").value(operation.id().asString()))
                .andExpect(jsonPath("$.type").value("CHECKOUT"))
                .andExpect(jsonPath("$.state").value("QUEUED"));

        verify(branchService).checkout(eq(RepositoryId.of(id)), eq("feature/x"));
    }

    @Test
    void checkout_blankRefIs400() throws Exception {
        when(branchService.checkout(any(), any()))
                .thenThrow(new IllegalArgumentException("'ref' must not be blank"));

        mvc.perform(post("/api/v1/repositories/" + UUID.randomUUID() + "/checkout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void checkout_unknownRepositoryIs404() throws Exception {
        when(branchService.checkout(any(), any()))
                .thenThrow(new NoSuchElementException("Repository not found"));

        mvc.perform(post("/api/v1/repositories/" + UUID.randomUUID() + "/checkout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ref\":\"main\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void checkout_providerWithoutBranchSupportIsCapabilityError() throws Exception {
        when(branchService.checkout(any(), any()))
                .thenThrow(new UnsupportedOperationException("SVN has no BranchOperations"));

        mvc.perform(post("/api/v1/repositories/" + UUID.randomUUID() + "/checkout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ref\":\"main\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CAPABILITY_NOT_SUPPORTED"));
    }

    @Test
    void createBranch_forwardsEveryField() throws Exception {
        when(branchService.createBranch(any(), any(), any(), anyBoolean()))
                .thenReturn(queued(OperationType.BRANCH_CREATE));
        String id = UUID.randomUUID().toString();

        mvc.perform(post("/api/v1/repositories/" + id + "/branches")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"feature/x\",\"startPoint\":\"main\",\"checkout\":true}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.type").value("BRANCH_CREATE"));

        verify(branchService).createBranch(
                eq(RepositoryId.of(id)), eq("feature/x"), eq("main"), eq(true));
    }

    @Test
    void createBranch_omittedFlagsDefaultToNotCheckingOut() throws Exception {
        when(branchService.createBranch(any(), any(), any(), anyBoolean()))
                .thenReturn(queued(OperationType.BRANCH_CREATE));

        mvc.perform(post("/api/v1/repositories/" + UUID.randomUUID() + "/branches")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"feature/x\"}"))
                .andExpect(status().isAccepted());

        verify(branchService).createBranch(any(), eq("feature/x"), isNull(), eq(false));
    }

    @Test
    void createBranch_missingBodyIs400() throws Exception {
        mvc.perform(post("/api/v1/repositories/" + UUID.randomUUID() + "/branches")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        verify(branchService, never()).createBranch(any(), any(), any(), anyBoolean());
    }

    @Test
    void merge_answers202WithTheQueuedOperation() throws Exception {
        when(branchService.merge(any(), any(), anyBoolean(), anyBoolean()))
                .thenReturn(queued(OperationType.MERGE));
        String id = UUID.randomUUID().toString();

        mvc.perform(post("/api/v1/repositories/" + id + "/merge")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"source\":\"feature/x\",\"fastForwardOnly\":true}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.type").value("MERGE"))
                .andExpect(jsonPath("$.state").value("QUEUED"));

        verify(branchService).merge(eq(RepositoryId.of(id)), eq("feature/x"), eq(true), eq(false));
    }

    @Test
    void merge_omittedFlagsDefaultToFalse() throws Exception {
        when(branchService.merge(any(), any(), anyBoolean(), anyBoolean()))
                .thenReturn(queued(OperationType.MERGE));

        mvc.perform(post("/api/v1/repositories/" + UUID.randomUUID() + "/merge")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"source\":\"feature/x\"}"))
                .andExpect(status().isAccepted());

        verify(branchService).merge(any(), eq("feature/x"), eq(false), eq(false));
    }

    @Test
    void merge_missingBodyIs400() throws Exception {
        mvc.perform(post("/api/v1/repositories/" + UUID.randomUUID() + "/merge")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        verify(branchService, never()).merge(any(), any(), anyBoolean(), anyBoolean());
    }

    @Test
    void merge_blankSourceIs400() throws Exception {
        when(branchService.merge(any(), any(), anyBoolean(), anyBoolean()))
                .thenThrow(new IllegalArgumentException("'source' must not be blank"));

        mvc.perform(post("/api/v1/repositories/" + UUID.randomUUID() + "/merge")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"source\":\"  \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void deleteBranch_handlesASlashInTheName() throws Exception {
        when(branchService.deleteBranch(any(), any(), anyBoolean(), anyBoolean()))
                .thenReturn(queued(OperationType.BRANCH_DELETE));
        String id = UUID.randomUUID().toString();

        // A plain path variable would stop at the first slash and lose "/x".
        mvc.perform(delete("/api/v1/repositories/" + id + "/branches/feature/x"))
                .andExpect(status().isAccepted());

        verify(branchService).deleteBranch(
                eq(RepositoryId.of(id)), eq("feature/x"), eq(false), eq(false));
    }

    @Test
    void deleteBranch_passesTheRemoteAndForceFlags() throws Exception {
        when(branchService.deleteBranch(any(), any(), anyBoolean(), anyBoolean()))
                .thenReturn(queued(OperationType.BRANCH_DELETE));

        mvc.perform(delete("/api/v1/repositories/" + UUID.randomUUID() + "/branches/origin/main")
                        .param("remote", "true")
                        .param("force", "true"))
                .andExpect(status().isAccepted());

        verify(branchService).deleteBranch(any(), eq("origin/main"), eq(true), eq(true));
    }

    @Test
    void deleteBranch_unknownBranchIs404() throws Exception {
        when(branchService.deleteBranch(any(), any(), anyBoolean(), anyBoolean()))
                .thenThrow(new NoSuchElementException("Branch not found: nope"));

        mvc.perform(delete("/api/v1/repositories/" + UUID.randomUUID() + "/branches/nope"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void deleteBranch_unmergedWithoutForceIs409() throws Exception {
        when(branchService.deleteBranch(any(), any(), anyBoolean(), anyBoolean()))
                .thenThrow(new VcsPreconditionException("Branch feature/x is not fully merged"));

        // Neither the request nor the server is at fault: the repository state is.
        mvc.perform(delete("/api/v1/repositories/" + UUID.randomUUID() + "/branches/feature/x"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));
    }

    @Test
    void checkout_blockedByLocalChangesIs409() throws Exception {
        when(branchService.checkout(any(), any())).thenThrow(new VcsPreconditionException(
                "Local changes would be overwritten by checkout: app.txt"));

        mvc.perform(post("/api/v1/repositories/" + UUID.randomUUID() + "/checkout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ref\":\"feature/x\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"))
                .andExpect(jsonPath("$.detail").value(
                        "Local changes would be overwritten by checkout: app.txt"));
    }
}

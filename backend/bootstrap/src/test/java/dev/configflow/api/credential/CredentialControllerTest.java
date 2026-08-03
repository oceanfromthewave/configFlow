package dev.configflow.api.credential;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.configflow.application.credential.CredentialService;
import dev.configflow.domain.credential.CredentialId;
import dev.configflow.domain.credential.CredentialRef;
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
 * Web-slice test of the credential controller: HTTP mapping, DTO shape and the
 * exception-to-Problem-Details translation, with the service mocked. The property that
 * matters most is negative — no response ever carries the secret or the store key.
 */
@WebMvcTest(CredentialController.class)
class CredentialControllerTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private CredentialService credentialService;

    private static CredentialRef sample() {
        return CredentialRef.issue("github.com", "https", "alice", "os-store-key-xyz", NOW);
    }

    @Test
    void save_returnsTheCredentialWithoutTheSecretOrStoreKey() throws Exception {
        when(credentialService.save(any(), any(), any(), any())).thenReturn(sample());

        mvc.perform(post("/api/v1/credentials")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"host\":\"github.com\",\"protocol\":\"https\","
                                + "\"username\":\"alice\",\"secret\":\"tok\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.host").value("github.com"))
                .andExpect(jsonPath("$.username").value("alice"))
                // The reference-only model, enforced at the API surface.
                .andExpect(jsonPath("$.secret").doesNotExist())
                .andExpect(jsonPath("$.storeKey").doesNotExist());
    }

    @Test
    void save_forwardsEveryFieldWithTheSecretAsChars() throws Exception {
        when(credentialService.save(any(), any(), any(), any())).thenReturn(sample());

        mvc.perform(post("/api/v1/credentials")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"host\":\"github.com\",\"protocol\":\"https\","
                                + "\"username\":\"alice\",\"secret\":\"tok\"}"))
                .andExpect(status().isOk());

        ArgumentCaptor<char[]> secret = ArgumentCaptor.forClass(char[].class);
        verify(credentialService).save(eq("github.com"), eq("https"), eq("alice"), secret.capture());
        assertEquals("tok", new String(secret.getValue()));
    }

    @Test
    void save_missingSecretIs400() throws Exception {
        mvc.perform(post("/api/v1/credentials")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"host\":\"github.com\",\"protocol\":\"https\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        verify(credentialService, never()).save(any(), any(), any(), any());
    }

    @Test
    void save_blankHostIs400() throws Exception {
        when(credentialService.save(any(), any(), any(), any()))
                .thenThrow(new IllegalArgumentException("'host' must not be blank"));

        mvc.perform(post("/api/v1/credentials")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"host\":\"  \",\"protocol\":\"https\",\"secret\":\"tok\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void list_returnsCredentialsWithoutSecrets() throws Exception {
        when(credentialService.list()).thenReturn(List.of(sample()));

        mvc.perform(get("/api/v1/credentials"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].host").value("github.com"))
                .andExpect(jsonPath("$[0].username").value("alice"))
                .andExpect(jsonPath("$[0].secret").doesNotExist())
                .andExpect(jsonPath("$[0].storeKey").doesNotExist());
    }

    @Test
    void delete_returns204AndForwardsTheId() throws Exception {
        String id = UUID.randomUUID().toString();

        mvc.perform(delete("/api/v1/credentials/" + id))
                .andExpect(status().isNoContent());

        verify(credentialService).delete(CredentialId.of(id));
    }

    @Test
    void delete_unknownIdIs404() throws Exception {
        doThrow(new NoSuchElementException("No such credential"))
                .when(credentialService).delete(any());

        mvc.perform(delete("/api/v1/credentials/" + UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void delete_malformedIdIs400() throws Exception {
        mvc.perform(delete("/api/v1/credentials/not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        verify(credentialService, never()).delete(any());
    }

    // --- createSshKey ------------------------------------------------------

    @Test
    void createSshKey_returnsThePublicKeyAndNothingSecret() throws Exception {
        CredentialRef sshKey = CredentialRef.issueSshkey(
                "github.com", "git", "os-store-key-xyz", "ssh-ed25519 AAAA... git@laptop", NOW);
        when(credentialService.createSshKey(any(), any(), any())).thenReturn(sshKey);

        mvc.perform(post("/api/v1/credentials/ssh-keys")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"host\":\"github.com\",\"username\":\"git\",\"comment\":\"git@laptop\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.host").value("github.com"))
                .andExpect(jsonPath("$.publicKey").value("ssh-ed25519 AAAA... git@laptop"))
                .andExpect(jsonPath("$.secret").doesNotExist())
                .andExpect(jsonPath("$.storeKey").doesNotExist());

        verify(credentialService).createSshKey("github.com", "git", "git@laptop");
    }

    @Test
    void createSshKey_withoutABodyIs400() throws Exception {
        mvc.perform(post("/api/v1/credentials/ssh-keys")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        verify(credentialService, never()).createSshKey(any(), any(), any());
    }

    @Test
    void createSshKey_blankHostIs400() throws Exception {
        when(credentialService.createSshKey(any(), any(), any()))
                .thenThrow(new IllegalArgumentException("'host' must not be blank"));

        mvc.perform(post("/api/v1/credentials/ssh-keys")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"host\":\"  \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void list_returnsThePublicKeyForAnSshCredential() throws Exception {
        CredentialRef sshKey = CredentialRef.issueSshkey(
                "github.com", "git", "os-store-key-xyz", "ssh-ed25519 AAAA... git@laptop", NOW);
        when(credentialService.list()).thenReturn(List.of(sshKey));

        mvc.perform(get("/api/v1/credentials"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].publicKey").value("ssh-ed25519 AAAA... git@laptop"));
    }
}

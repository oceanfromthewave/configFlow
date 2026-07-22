package dev.configflow.api.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
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
import dev.configflow.domain.vcs.model.Author;
import dev.configflow.domain.vcs.model.ChangeType;
import dev.configflow.domain.vcs.model.CommitRequest;
import dev.configflow.domain.vcs.model.DiffHunk;
import dev.configflow.domain.vcs.model.FileChange;
import dev.configflow.domain.vcs.model.FileDiff;
import dev.configflow.domain.vcs.model.HistoryQuery;
import dev.configflow.domain.vcs.model.Page;
import dev.configflow.domain.vcs.model.RefLabel;
import dev.configflow.domain.vcs.model.Revision;
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
                List.of(FileChange.of(Path.of("src/staged.txt"), ChangeType.ADDED)),
                List.of(FileChange.of(Path.of("changed.txt"), ChangeType.MODIFIED)),
                List.of());
        when(repositoryService.status(any())).thenReturn(status);

        mvc.perform(get("/api/v1/repositories/" + UUID.randomUUID() + "/status"))
                .andExpect(status().isOk())
                // Always '/', even though Path renders '\' on Windows.
                .andExpect(jsonPath("$.staged[0].path").value("src/staged.txt"))
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
    void history_mapsEveryFilterOntoTheQuery() throws Exception {
        when(repositoryService.history(any(), any())).thenReturn(new Page<>(List.of(), null));
        String id = UUID.randomUUID().toString();

        mvc.perform(get("/api/v1/repositories/" + id + "/history")
                        .param("cursor", "abc123")
                        .param("limit", "25")
                        .param("branch", "main")
                        .param("author", "alice")
                        .param("message", "fix")
                        .param("path", "src/app.ts")
                        .param("from", "2026-01-01T00:00:00Z")
                        .param("to", "2026-02-01T00:00:00Z"))
                .andExpect(status().isOk());

        ArgumentCaptor<HistoryQuery> captor = ArgumentCaptor.forClass(HistoryQuery.class);
        verify(repositoryService).history(eq(RepositoryId.of(id)), captor.capture());
        HistoryQuery query = captor.getValue();
        assertEquals("abc123", query.cursor());
        assertEquals(25, query.limit());
        assertEquals("main", query.branch());
        assertEquals("alice", query.author());
        assertEquals("fix", query.messageContains());
        assertEquals(Path.of("src/app.ts"), query.path());
        assertEquals(Instant.parse("2026-01-01T00:00:00Z"), query.from());
        assertEquals(Instant.parse("2026-02-01T00:00:00Z"), query.to());
    }

    @Test
    void history_omittedFiltersBecomeNullAndBlanksAreDropped() throws Exception {
        when(repositoryService.history(any(), any())).thenReturn(new Page<>(List.of(), null));

        mvc.perform(get("/api/v1/repositories/" + UUID.randomUUID() + "/history")
                        .param("author", "   "))
                .andExpect(status().isOk());

        ArgumentCaptor<HistoryQuery> captor = ArgumentCaptor.forClass(HistoryQuery.class);
        verify(repositoryService).history(any(), captor.capture());
        HistoryQuery query = captor.getValue();
        assertEquals(50, query.limit(), "the default page size");
        assertNull(query.cursor());
        // A blank filter means "no filter", not "match the empty string".
        assertNull(query.author());
        assertNull(query.branch());
        assertNull(query.path());
        assertNull(query.from());
    }

    @Test
    void history_trimsThePathFilter() throws Exception {
        when(repositoryService.history(any(), any())).thenReturn(new Page<>(List.of(), null));

        mvc.perform(get("/api/v1/repositories/" + UUID.randomUUID() + "/history")
                        .param("path", "  src/app.ts  "))
                .andExpect(status().isOk());

        ArgumentCaptor<HistoryQuery> captor = ArgumentCaptor.forClass(HistoryQuery.class);
        verify(repositoryService).history(any(), captor.capture());
        // Untrimmed, the surrounding spaces become part of the path and match nothing.
        assertEquals(Path.of("src/app.ts"), captor.getValue().path());
    }

    @Test
    void history_malformedRevisionSyntaxIs400() throws Exception {
        when(repositoryService.history(any(), any()))
                .thenThrow(new IllegalArgumentException("Not a valid branch or cursor"));

        mvc.perform(get("/api/v1/repositories/" + UUID.randomUUID() + "/history")
                        .param("branch", "a:b:c"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void history_serialisesRevisionsWithFlatIds() throws Exception {
        Revision revision = new Revision(
                new RevisionId("0123456789abcdef"),
                List.of(new RevisionId("fedcba9876543210")),
                new Author("Alice", "alice@configflow.dev"),
                NOW,
                "feat: add a thing",
                List.of(new RefLabel(RefLabel.Kind.BRANCH, "main")));
        when(repositoryService.history(any(), any()))
                .thenReturn(new Page<>(List.of(revision), "cursor-2"));

        mvc.perform(get("/api/v1/repositories/" + UUID.randomUUID() + "/history"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value("0123456789abcdef"))
                .andExpect(jsonPath("$.items[0].parents[0]").value("fedcba9876543210"))
                .andExpect(jsonPath("$.items[0].author.name").value("Alice"))
                .andExpect(jsonPath("$.items[0].message").value("feat: add a thing"))
                .andExpect(jsonPath("$.items[0].labels[0].kind").value("BRANCH"))
                .andExpect(jsonPath("$.nextCursor").value("cursor-2"));
    }

    @Test
    void history_unparseableTimestampIs400() throws Exception {
        mvc.perform(get("/api/v1/repositories/" + UUID.randomUUID() + "/history")
                        .param("from", "yesterday"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        verify(repositoryService, never()).history(any(), any());
    }

    @Test
    void history_nonNumericLimitIs400() throws Exception {
        // Spring fails to bind before our code runs; without a handler this is a 500.
        mvc.perform(get("/api/v1/repositories/" + UUID.randomUUID() + "/history")
                        .param("limit", "many"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void register_malformedJsonBodyIs400() throws Exception {
        mvc.perform(post("/api/v1/repositories")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"localPath\":"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void show_returnsTheRevision() throws Exception {
        when(repositoryService.show(any(), any())).thenReturn(new Revision(
                new RevisionId("0123456789abcdef"),
                List.of(),
                new Author("Alice", null),
                NOW,
                "only commit",
                List.of()));

        mvc.perform(get("/api/v1/repositories/" + UUID.randomUUID() + "/commits/HEAD"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("0123456789abcdef"))
                .andExpect(jsonPath("$.author.email").doesNotExist());

        verify(repositoryService).show(any(), eq(new RevisionId("HEAD")));
    }

    @Test
    void show_unknownRevisionIs404() throws Exception {
        when(repositoryService.show(any(), any()))
                .thenThrow(new NoSuchElementException("Revision not found"));

        mvc.perform(get("/api/v1/repositories/" + UUID.randomUUID() + "/commits/deadbeef"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void diffWorking_returnsHunksAndForwardsTheStagedFlag() throws Exception {
        FileDiff diff = new FileDiff(
                Path.of("src/app.ts"), null, ChangeType.MODIFIED, false,
                List.of(new DiffHunk(1, 3, 1, 4, List.of(" keep", "-old", "+new", "+extra"))));
        when(repositoryService.diffWorking(any(), any(), anyBoolean())).thenReturn(diff);
        String id = UUID.randomUUID().toString();

        mvc.perform(get("/api/v1/repositories/" + id + "/diff")
                        .param("path", "src/app.ts")
                        .param("staged", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.path").value("src/app.ts"))
                .andExpect(jsonPath("$.type").value("MODIFIED"))
                .andExpect(jsonPath("$.binary").value(false))
                .andExpect(jsonPath("$.hunks[0].newCount").value(4))
                .andExpect(jsonPath("$.hunks[0].lines[1]").value("-old"));

        verify(repositoryService).diffWorking(
                eq(RepositoryId.of(id)), eq(Path.of("src/app.ts")), eq(true));
    }

    @Test
    void diffWorking_defaultsToTheUnstagedSide() throws Exception {
        when(repositoryService.diffWorking(any(), any(), anyBoolean())).thenReturn(
                new FileDiff(Path.of("a.txt"), null, ChangeType.MODIFIED, false, List.of()));

        mvc.perform(get("/api/v1/repositories/" + UUID.randomUUID() + "/diff")
                        .param("path", "a.txt"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hunks").isEmpty());

        verify(repositoryService).diffWorking(any(), any(), eq(false));
    }

    @Test
    void diffWorking_reportsBinaryFilesWithoutHunks() throws Exception {
        when(repositoryService.diffWorking(any(), any(), anyBoolean())).thenReturn(
                new FileDiff(Path.of("logo.png"), null, ChangeType.ADDED, true, List.of()));

        mvc.perform(get("/api/v1/repositories/" + UUID.randomUUID() + "/diff")
                        .param("path", "logo.png"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.binary").value(true))
                .andExpect(jsonPath("$.hunks").isEmpty());
    }

    @Test
    void diffWorking_missingPathIs400() throws Exception {
        mvc.perform(get("/api/v1/repositories/" + UUID.randomUUID() + "/diff"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        verify(repositoryService, never()).diffWorking(any(), any(), anyBoolean());
    }

    @Test
    void diffWorking_blankPathIs400() throws Exception {
        mvc.perform(get("/api/v1/repositories/" + UUID.randomUUID() + "/diff")
                        .param("path", "  "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        verify(repositoryService, never()).diffWorking(any(), any(), anyBoolean());
    }

    @Test
    void diffWorking_reportsARenameWithItsPreviousPath() throws Exception {
        when(repositoryService.diffWorking(any(), any(), anyBoolean())).thenReturn(
                new FileDiff(Path.of("new.txt"), Path.of("old.txt"), ChangeType.RENAMED,
                        false, List.of()));

        mvc.perform(get("/api/v1/repositories/" + UUID.randomUUID() + "/diff")
                        .param("path", "new.txt"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("RENAMED"))
                .andExpect(jsonPath("$.oldPath").value("old.txt"));
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

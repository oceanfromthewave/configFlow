package dev.configflow.domain.operation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.configflow.domain.vcs.exception.MergeConflictException;
import dev.configflow.domain.vcs.exception.VcsAuthenticationRequiredException;
import dev.configflow.domain.vcs.exception.VcsException;
import dev.configflow.domain.vcs.exception.VcsNetworkException;
import dev.configflow.domain.vcs.exception.VcsPreconditionException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import org.junit.jupiter.api.Test;

class OperationFailuresTest {

    @Test
    void anAuthFailureCarriesWhatTheCredentialPromptNeedsToAsk() {
        OperationFailure failure = OperationFailures.classify(
                new VcsAuthenticationRequiredException("github.com", "https", null));

        assertEquals(OperationFailures.VCS_AUTH_REQUIRED, failure.code());
        // "Ask for credentials" is not an answer on its own — credentials for what?
        assertEquals("github.com", failure.context().get("host"));
        assertEquals("https", failure.context().get("protocol"));
    }

    @Test
    void eachKindOfFailureGetsItsOwnName() {
        assertEquals(OperationFailures.MERGE_CONFLICT,
                OperationFailures.classify(
                        new MergeConflictException(List.of(Path.of("a.txt")))).code());
        assertEquals(OperationFailures.CONFLICT,
                OperationFailures.classify(new VcsPreconditionException("dirty")).code());
        assertEquals(OperationFailures.VCS_NETWORK_ERROR,
                OperationFailures.classify(new VcsNetworkException("unreachable", null)).code());
        assertEquals(OperationFailures.NOT_FOUND,
                OperationFailures.classify(new NoSuchElementException("gone")).code());
        assertEquals(OperationFailures.CAPABILITY_NOT_SUPPORTED,
                OperationFailures.classify(new UnsupportedOperationException("no")).code());
        assertEquals(OperationFailures.VALIDATION_ERROR,
                OperationFailures.classify(new IllegalArgumentException("blank")).code());
    }

    @Test
    void aMergeConflictIsNotJustAnyPreconditionFailure() {
        // Siblings today, and the frontend routes them differently: one opens the resolve
        // flow, the other only says the working copy is in the way. If MergeConflict ever
        // becomes a subclass, the switch above it here is what keeps them apart.
        assertEquals(OperationFailures.MERGE_CONFLICT,
                OperationFailures.classify(
                        new MergeConflictException(List.of(Path.of("a.txt")))).code());
    }

    @Test
    void anUnrecognisedFailureIsNotGivenAnAnswerItCannotSupport() {
        // A plain VcsException is an engine or IO failure: real, but nothing the user can
        // act on. Naming it VCS_NETWORK_ERROR would offer "try again later" for a bug.
        assertEquals(OperationFailures.INTERNAL_ERROR,
                OperationFailures.classify(new VcsException("jgit blew up")).code());
        assertEquals(OperationFailures.INTERNAL_ERROR,
                OperationFailures.classify(new IllegalStateException("index.lock")).code());
        assertEquals(OperationFailures.INTERNAL_ERROR,
                OperationFailures.classify(new StackOverflowError("too deep")).code());
    }

    @Test
    void aFailureWithNothingToSayStillSaysSomething() {
        // NullPointerException usually arrives without a message, and an empty failure
        // tells the user nothing at all.
        assertEquals("NullPointerException",
                OperationFailures.classify(new NullPointerException()).message());
        assertEquals("IllegalStateException",
                OperationFailures.classify(new IllegalStateException("   ")).message());
    }

    @Test
    void classifyingNothingDoesNotThrow() {
        // The queue classifies whatever it caught. It is never null today, but a
        // classifier that throws while explaining a failure is the worst kind of bug.
        OperationFailure failure = OperationFailures.classify(null);

        assertEquals(OperationFailures.INTERNAL_ERROR, failure.code());
        assertEquals("Unknown failure", failure.message());
    }

    @Test
    void aFailureCannotBeChangedAfterTheFact() {
        Map<String, String> mutable = new HashMap<>();
        mutable.put("host", "github.com");
        OperationFailure failure = new OperationFailure("X", "detail", mutable);

        mutable.put("host", "evil.example");

        assertEquals("github.com", failure.context().get("host"));
        assertThrows(UnsupportedOperationException.class,
                () -> failure.context().put("host", "evil.example"));
    }

    @Test
    void aFailureWithoutContextHasAnEmptyOneRatherThanNull() {
        assertTrue(OperationFailure.of("X", "detail").context().isEmpty());
        assertTrue(new OperationFailure("X", "detail", null).context().isEmpty());
    }
}

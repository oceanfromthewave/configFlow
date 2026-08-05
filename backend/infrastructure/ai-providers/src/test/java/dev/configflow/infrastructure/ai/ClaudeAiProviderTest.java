package dev.configflow.infrastructure.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.configflow.domain.ai.AiFeature;
import dev.configflow.domain.ai.AiProviderException;
import dev.configflow.domain.ai.DiffContext;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * Covers the key-supplier indirection: {@link ClaudeAiProvider} reads the key on every
 * call rather than once at construction, so a key rotated in the OS store takes effect
 * without restarting the app.
 */
class ClaudeAiProviderTest {

    @Test
    void supportedFeaturesIsEmptyWhenNoKeyIsConfigured() {
        ClaudeAiProvider provider = new ClaudeAiProvider(() -> "", "claude-opus-5");

        assertEquals(Set.of(), provider.supportedFeatures());
    }

    @Test
    void supportedFeaturesIsEmptyWhenTheSupplierReturnsNull() {
        ClaudeAiProvider provider = new ClaudeAiProvider(() -> null, "claude-opus-5");

        assertEquals(Set.of(), provider.supportedFeatures());
    }

    @Test
    void supportedFeaturesIncludesCommitMessageOnceAKeyIsPresent() {
        ClaudeAiProvider provider = new ClaudeAiProvider(() -> "sk-ant-test", "claude-opus-5");

        assertEquals(Set.of(AiFeature.COMMIT_MESSAGE), provider.supportedFeatures());
    }

    @Test
    void supportedFeaturesTracksTheSupplierAcrossCalls() {
        // Simulates rotation: the caller edits the credential and the very next call
        // must see it, with no provider rebuild in between.
        AtomicReference<String> key = new AtomicReference<>("");
        ClaudeAiProvider provider = new ClaudeAiProvider(key::get, "claude-opus-5");

        assertEquals(Set.of(), provider.supportedFeatures());

        key.set("sk-ant-test");
        assertEquals(Set.of(AiFeature.COMMIT_MESSAGE), provider.supportedFeatures());

        key.set("");
        assertEquals(Set.of(), provider.supportedFeatures());
    }

    @Test
    void generateCommitMessageFailsWithAuthReasonWhenTheKeyDisappearsBeforeTheCall() {
        // supportedFeatures() gates the caller, but the key can still vanish between that
        // check and the actual send() — this is the second line of defense.
        ClaudeAiProvider provider = new ClaudeAiProvider(() -> "", "claude-opus-5");

        AiProviderException e = assertThrows(AiProviderException.class,
                () -> provider.generateCommitMessage(new DiffContext("diff", null)));

        assertEquals(AiProviderException.Reason.AUTH, e.reason());
        assertTrue(e.getMessage().contains("키"));
    }
}

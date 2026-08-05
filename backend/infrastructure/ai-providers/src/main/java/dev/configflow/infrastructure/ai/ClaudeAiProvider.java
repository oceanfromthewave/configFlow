package dev.configflow.infrastructure.ai;

import dev.configflow.domain.ai.AiFeature;
import dev.configflow.domain.ai.AiProvider;
import dev.configflow.domain.ai.AiResult;
import dev.configflow.domain.ai.ConflictContext;
import dev.configflow.domain.ai.DiffContext;
import dev.configflow.domain.ai.MergeProposal;
import dev.configflow.domain.ai.ReviewReport;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Objects;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Anthropic Messages API를 호출하는 AI 제공자.
 *
 * <p>JDK {@link HttpClient}만 사용한다(HTTP 클라이언트 의존성 없음). 이 슬라이스에서는
 * 커밋 메시지 생성만 지원하며, 나머지 기능은 {@link NoopAiProvider}와 같이 거부한다.</p>
 *
 * <p>시크릿 마스킹은 {@link DiffContext} 규약대로 호출자 책임이다. 여기서는 전달받은
 * diff를 그대로 보낸다.</p>
 */
public final class ClaudeAiProvider implements AiProvider {

    private static final URI MESSAGES_ENDPOINT = URI.create("https://api.anthropic.com/v1/messages");
    private static final String ANTHROPIC_VERSION = "2023-06-01";
    private static final int MAX_TOKENS = 2048;
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(60);

    /** 오류 메시지에 실어 나르는 응답 본문 길이 상한. */
    private static final int ERROR_BODY_LIMIT = 500;

    private static final String COMMIT_MESSAGE_SYSTEM_PROMPT = """
            You write git commit messages from a unified diff.

            Rules:
            - Follow Conventional Commits (feat, fix, docs, refactor, test, chore, ...).
            - Write in English.
            - Subject line: at most 72 characters, imperative mood, no trailing period.
            - Add a short body only when the diff needs explanation; separate it with a blank line.
            - Output the commit message only. No preamble, no code fences, no commentary.
            """;

    private final HttpClient http;
    private final ObjectMapper mapper;
    private final String apiKey;
    private final String model;

    public ClaudeAiProvider(String apiKey, String model) {
        this.apiKey = requireText(apiKey, "apiKey");
        this.model = requireText(model, "model");
        this.http = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
        this.mapper = JsonMapper.builder().build();
    }

    @Override
    public String id() {
        return "claude";
    }

    @Override
    public Set<AiFeature> supportedFeatures() {
        return Set.of(AiFeature.COMMIT_MESSAGE);
    }

    @Override
    public AiResult<String> generateCommitMessage(DiffContext context) {
        Objects.requireNonNull(context, "context must not be null");
        String body = requestBody(COMMIT_MESSAGE_SYSTEM_PROMPT, userContent(context));
        return AiResult.of(firstTextBlock(send(body)));
    }

    @Override
    public AiResult<String> summarizeChanges(DiffContext context) {
        throw unsupported(AiFeature.CHANGE_SUMMARY);
    }

    @Override
    public AiResult<MergeProposal> resolveConflict(ConflictContext context) {
        throw unsupported(AiFeature.CONFLICT_RESOLUTION);
    }

    @Override
    public AiResult<ReviewReport> reviewCode(DiffContext context) {
        throw unsupported(AiFeature.CODE_REVIEW);
    }

    /** 브랜치 정보가 있으면 diff 앞에 덧붙인다. */
    private static String userContent(DiffContext context) {
        if (context.branch() == null || context.branch().isBlank()) {
            return context.unifiedDiff();
        }
        return "Branch: " + context.branch() + "\n\n" + context.unifiedDiff();
    }

    private String requestBody(String system, String userMessage) {
        ObjectNode root = mapper.createObjectNode();
        root.put("model", model);
        root.put("max_tokens", MAX_TOKENS);
        // 커밋 메시지는 깊은 추론이 필요 없다. thinking은 켠 채 두고 effort만 낮춘다.
        root.putObject("output_config").put("effort", "low");
        root.put("system", system);

        ObjectNode message = root.putArray("messages").addObject();
        message.put("role", "user");
        message.put("content", userMessage);

        return mapper.writeValueAsString(root);
    }

    private String send(String body) {
        HttpRequest request = HttpRequest.newBuilder(MESSAGES_ENDPOINT)
                .timeout(REQUEST_TIMEOUT)
                .header("x-api-key", apiKey)
                .header("anthropic-version", ANTHROPIC_VERSION)
                .header("content-type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new IllegalStateException("Claude API 호출에 실패했습니다", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Claude API 호출이 중단되었습니다", e);
        }

        if (response.statusCode() / 100 != 2) {
            // API 키는 절대 메시지에 싣지 않는다. 본문도 길면 잘라서 로그 오염을 막는다.
            throw new IllegalStateException("Claude API가 " + response.statusCode() + "를 반환했습니다: " + truncate(response.body()));
        }
        return response.body();
    }

    /**
     * 응답에서 첫 번째 text 블록을 꺼낸다.
     *
     * <p>thinking이 켜져 있으면 {@code content[0]}이 thinking 블록일 수 있으므로 인덱스로
     * 접근하지 않고 타입으로 찾는다.</p>
     */
    private String firstTextBlock(String responseBody) {
        JsonNode root = mapper.readTree(responseBody);
        for (JsonNode block : root.path("content")) {
            if ("text".equals(block.path("type").asString())) {
                String text = block.path("text").asString().trim();
                if (!text.isEmpty()) {
                    return text;
                }
            }
        }
        throw new IllegalStateException("Claude 응답에 text 블록이 없습니다: " + truncate(responseBody));
    }

    private static String truncate(String body) {
        if (body == null) {
            return "";
        }
        return body.length() <= ERROR_BODY_LIMIT ? body : body.substring(0, ERROR_BODY_LIMIT) + "...";
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    private static UnsupportedOperationException unsupported(AiFeature feature) {
        return new UnsupportedOperationException("claude provider does not support " + feature);
    }
}

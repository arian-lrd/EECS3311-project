package ca.yorku.eecs3311.huntassist.review;

import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

@Component
class OllamaRanker implements LocalRanker {
    private static final URI LOCAL_CHAT = URI.create("http://127.0.0.1:11434/api/chat");
    private static final int MAX_RESPONSE_BYTES = 32 * 1024;
    private static final int REQUEST_TIMEOUT_SECONDS = 20;
    private static final Pattern MODEL_NAME = Pattern.compile("[a-zA-Z0-9][a-zA-Z0-9._:/-]{0,99}");
    private static final String INSTRUCTIONS = """
            Rank the provided defensive maintenance actions using the observed booleans.
            Consider output encoding the primary XSS control and a restrictive Content Security Policy a second layer.
            Return exactly a JSON object with one key, "order", whose value is an array containing every allowed
            action code exactly once. Use no other text, fields, actions, or examples.
            """;

    private final String model;
    private final URI endpoint;
    private final JsonMapper json = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
            .build();
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .followRedirects(HttpClient.Redirect.NEVER)
            .proxy(HttpClient.Builder.NO_PROXY)
            .build();

    @Autowired
    OllamaRanker(@Value("${huntassist.ollama.model:}") String model) {
        this(model, LOCAL_CHAT);
    }

    OllamaRanker(String model, URI endpoint) {
        if (!"http".equals(endpoint.getScheme()) || !"127.0.0.1".equals(endpoint.getHost())
                || !"/api/chat".equals(endpoint.getPath()) || endpoint.getUserInfo() != null
                || endpoint.getRawQuery() != null || endpoint.getRawFragment() != null) {
            throw new IllegalArgumentException("Ollama must use the loopback chat endpoint.");
        }
        this.endpoint = endpoint;
        this.model = model.strip();
    }

    @Override
    public Ranking rank(Map<VulnerabilityModule.Signal, Boolean> signals, List<DefensiveAction> allowed) {
        if (model.isEmpty()) {
            return failure(Status.MODEL_DISABLED);
        }
        if (!MODEL_NAME.matcher(model).matches()
                || model.toLowerCase(Locale.ROOT).matches(".*[-:/]cloud(?:[.:/].*)?")) {
            return failure(Status.MODEL_UNAVAILABLE);
        }
        // The request intentionally contains no free text from notes, the capture, or the question.
        var input = Map.of("signals", signals, "allowed_actions", allowed);
        var request = Map.of(
                "model", model, "stream", false, "format", "json",
                "options", Map.of("temperature", 0, "num_predict", 120),
                "messages", List.of(
                        Map.of("role", "system", "content", INSTRUCTIONS),
                        Map.of("role", "user", "content", json.writeValueAsString(input))));
        HttpRequest message = HttpRequest.newBuilder(endpoint)
                .timeout(Duration.ofSeconds(REQUEST_TIMEOUT_SECONDS))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(request)))
                .build();
        CompletableFuture<HttpResponse<String>> response =
                client.sendAsync(message, ignored -> new BoundedResponse(MAX_RESPONSE_BYTES));
        try {
            HttpResponse<String> result = response.get(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (result.statusCode() != 200) {
                return failure(Status.MODEL_UNAVAILABLE);
            }
            return readRanking(result.body(), allowed);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            return failure(Status.MODEL_UNAVAILABLE);
        } catch (ExecutionException | TimeoutException failure) {
            return failure(Status.MODEL_UNAVAILABLE);
        } finally {
            if (!response.isDone()) {
                response.cancel(true);
            }
        }
    }

    private Ranking readRanking(String body, List<DefensiveAction> allowed) {
        try {
            JsonNode response = json.readTree(body);
            if (response == null || !response.isObject()) {
                return failure(Status.MODEL_INVALID);
            }
            JsonNode modelOutput = response.path("message").path("content");
            if (!modelOutput.isString()) {
                return failure(Status.MODEL_INVALID);
            }
            JsonNode decision = json.readTree(modelOutput.stringValue());
            if (decision == null || !decision.isObject() || decision.size() != 1) {
                return failure(Status.MODEL_INVALID);
            }
            JsonNode order = decision.path("order");
            if (!order.isArray() || order.size() != allowed.size()) {
                return failure(Status.MODEL_INVALID);
            }
            List<DefensiveAction> ranked = new ArrayList<>();
            for (JsonNode value : order) {
                if (!value.isString()) {
                    return failure(Status.MODEL_INVALID);
                }
                ranked.add(DefensiveAction.valueOf(value.stringValue()));
            }
            if (!new HashSet<>(ranked).equals(new HashSet<>(allowed))) {
                return failure(Status.MODEL_INVALID);
            }
            return new Ranking(Status.MODEL_RANKED, ranked);
        } catch (tools.jackson.core.JacksonException | IllegalArgumentException failure) {
            return failure(Status.MODEL_INVALID);
        }
    }

    private static Ranking failure(Status status) {
        return new Ranking(status, List.of());
    }

    @PreDestroy
    void close() {
        client.shutdownNow();
    }

    private static final class BoundedResponse implements HttpResponse.BodySubscriber<String> {
        private final int limit;
        private final CompletableFuture<String> body = new CompletableFuture<>();
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private Flow.Subscription subscription;

        BoundedResponse(int limit) {
            this.limit = limit;
        }

        @Override
        public CompletionStage<String> getBody() {
            return body;
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            subscription.request(1);
        }

        @Override
        public void onNext(List<ByteBuffer> buffers) {
            for (ByteBuffer buffer : buffers) {
                if (buffer.remaining() > limit - bytes.size()) {
                    subscription.cancel();
                    body.completeExceptionally(new IOException("Model response exceeds the size limit."));
                    return;
                }
                byte[] chunk = new byte[buffer.remaining()];
                buffer.get(chunk);
                bytes.writeBytes(chunk);
            }
            subscription.request(1);
        }

        @Override
        public void onError(Throwable failure) {
            body.completeExceptionally(failure);
        }

        @Override
        public void onComplete() {
            body.complete(bytes.toString(StandardCharsets.UTF_8));
        }
    }
}

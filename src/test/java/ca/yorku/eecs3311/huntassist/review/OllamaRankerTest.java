package ca.yorku.eecs3311.huntassist.review;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static ca.yorku.eecs3311.huntassist.review.DefensiveAction.REVIEW_CONTENT_SECURITY_POLICY;
import static ca.yorku.eecs3311.huntassist.review.DefensiveAction.REVIEW_OUTPUT_ENCODING;
import static org.assertj.core.api.Assertions.assertThat;

class OllamaRankerTest {
    @Test
    void requestsOnlyTrustedInputsAndRejectsExtraModelOutput() throws Exception {
        var json = JsonMapper.builder().build();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var request = new AtomicReference<String>();
        var reply = new AtomicReference<String>("""
                {"order":["REVIEW_CONTENT_SECURITY_POLICY","REVIEW_OUTPUT_ENCODING"]}
                """);
        server.createContext("/api/chat", exchange -> {
            try (exchange) {
                request.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                byte[] bytes = json.writeValueAsBytes(Map.of("message", Map.of("content", reply.get())));
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
            }
        });
        server.start();
        var ranker = new OllamaRanker("test-local",
                URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/api/chat"));
        try {
            var signals = Map.of(VulnerabilityModule.Signal.HTML_RESPONSE_OBSERVED, true);
            var allowed = List.of(REVIEW_OUTPUT_ENCODING, REVIEW_CONTENT_SECURITY_POLICY);
            var ranked = ranker.rank(signals, allowed);
            assertThat(ranked.status()).isEqualTo(LocalRanker.Status.MODEL_RANKED);
            assertThat(ranked.order()).containsExactly(REVIEW_CONTENT_SECURITY_POLICY, REVIEW_OUTPUT_ENCODING);
            var prompt = json.readTree(request.get());
            assertThat(prompt.path("model").stringValue()).isEqualTo("test-local");
            var typedInput = json.readTree(prompt.path("messages").get(1).path("content").stringValue());
            assertThat(typedInput.propertyNames()).containsExactlyInAnyOrder("signals", "allowed_actions");
            assertThat(typedInput.path("signals").propertyNames()).containsExactly("HTML_RESPONSE_OBSERVED");

            reply.set("""
                    {"order":["REVIEW_OUTPUT_ENCODING","REVIEW_CONTENT_SECURITY_POLICY"],"explanation":"untrusted"}
                    """);
            assertThat(ranker.rank(signals, allowed).status()).isEqualTo(LocalRanker.Status.MODEL_INVALID);
            reply.set("""
                    {"order":["REVIEW_OUTPUT_ENCODING","REVIEW_OUTPUT_ENCODING"]}
                    """);
            assertThat(ranker.rank(signals, allowed).status()).isEqualTo(LocalRanker.Status.MODEL_INVALID);
            reply.set("""
                    {"order":["REVIEW_OUTPUT_ENCODING","REVIEW_CONTENT_SECURITY_POLICY"]} {}
                    """);
            assertThat(ranker.rank(signals, allowed).status()).isEqualTo(LocalRanker.Status.MODEL_INVALID);
            reply.set("""
                    {"order":[],"order":["REVIEW_OUTPUT_ENCODING","REVIEW_CONTENT_SECURITY_POLICY"]}
                    """);
            assertThat(ranker.rank(signals, allowed).status()).isEqualTo(LocalRanker.Status.MODEL_INVALID);
        } finally {
            ranker.close();
            server.stop(0);
        }
    }
}

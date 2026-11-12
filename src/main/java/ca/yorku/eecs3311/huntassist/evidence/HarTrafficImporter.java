package ca.yorku.eecs3311.huntassist.evidence;

import ca.yorku.eecs3311.huntassist.application.AppException;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Component
final class HarTrafficImporter implements TrafficImporter {
    private final JsonMapper mapper = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    @Override
    public boolean supports(int firstByte) {
        return firstByte == '{';
    }

    @Override
    public List<CapturedExchange> parse(byte[] source) {
        try {
            JsonNode entries = mapper.readTree(source).path("log").path("entries");
            if (!entries.isArray()) {
                throw AppException.invalid("Expected a HAR log with an entries array.");
            }
            if (entries.size() > MAX_ENTRIES) {
                throw AppException.invalid("Capture exceeds the 20,000-exchange limit.");
            }
            List<CapturedExchange> captures = new ArrayList<>(entries.size());
            for (JsonNode entry : entries) {
                JsonNode request = entry.path("request");
                JsonNode response = entry.path("response");
                JsonNode status = response.path("status");
                HeaderPresence headers = new HeaderPresence();
                for (JsonNode header : response.path("headers")) {
                    headers.accept(header.path("name").asText(""), header.path("value").asText(""));
                }
                captures.add(new CapturedExchange(request.path("url").asText(null),
                        request.path("method").asText(null),
                        status.isIntegralNumber() && status.canConvertToInt() ? status.intValue() : -1,
                        headers.mediaTypeOr(response.path("content").path("mimeType").asText("")), headers));
            }
            return captures;
        } catch (JacksonException e) {
            throw AppException.invalid("Invalid or unsupported HAR capture.");
        }
    }
}

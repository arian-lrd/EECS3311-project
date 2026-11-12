package ca.yorku.eecs3311.huntassist.review;

import ca.yorku.eecs3311.huntassist.evidence.EvidenceService.Observation;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

@Component
class XssMitigationModule implements VulnerabilityModule {
    private static final int MAX_EVIDENCE_REFS = 5;

    @Override
    public ReviewService.ModuleInfo info() {
        return new ReviewService.ModuleInfo("xss", "XSS mitigation",
                "Review captured HTML response metadata for applicable defensive controls.");
    }

    @Override
    public String knowledgeQuery() {
        return "XSS output encoding content security policy MIME nosniff";
    }

    @Override
    public Assessment assess(List<Observation> evidence) {
        List<Observation> html = evidence.stream()
                .filter(observation -> "text/html".equalsIgnoreCase(observation.contentType())
                        || "application/xhtml+xml".equalsIgnoreCase(observation.contentType()))
                .toList();
        if (html.isEmpty()) {
            return new Assessment(false, Map.of(Signal.HTML_RESPONSE_OBSERVED, false), List.of());
        }

        List<UUID> missingCsp = references(html, observation -> !observation.contentSecurityPolicy());
        List<UUID> missingContentTypeOptions = references(html, observation -> !observation.contentTypeOptions());
        List<Candidate> actions = new ArrayList<>();
        actions.add(new Candidate(DefensiveAction.REVIEW_OUTPUT_ENCODING, references(html, observation -> true)));
        if (!missingCsp.isEmpty()) {
            actions.add(new Candidate(DefensiveAction.REVIEW_CONTENT_SECURITY_POLICY, missingCsp));
        }
        if (!missingContentTypeOptions.isEmpty()) {
            actions.add(new Candidate(DefensiveAction.REVIEW_CONTENT_TYPE_OPTIONS, missingContentTypeOptions));
        }
        return new Assessment(true, Map.of(
                Signal.HTML_RESPONSE_OBSERVED, true,
                Signal.CONTENT_SECURITY_POLICY_NOT_OBSERVED, !missingCsp.isEmpty(),
                Signal.CONTENT_TYPE_OPTIONS_NOT_OBSERVED, !missingContentTypeOptions.isEmpty()), actions);
    }

    private static List<UUID> references(List<Observation> evidence, Predicate<Observation> filter) {
        return evidence.stream().filter(filter).map(Observation::id).limit(MAX_EVIDENCE_REFS).toList();
    }
}

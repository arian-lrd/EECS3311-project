package ca.yorku.eecs3311.huntassist.review;

import ca.yorku.eecs3311.huntassist.application.AppException;
import ca.yorku.eecs3311.huntassist.application.ProjectService;
import ca.yorku.eecs3311.huntassist.evidence.EvidenceService;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static ca.yorku.eecs3311.huntassist.review.DefensiveAction.REVIEW_CONTENT_SECURITY_POLICY;
import static ca.yorku.eecs3311.huntassist.review.DefensiveAction.REVIEW_CONTENT_TYPE_OPTIONS;
import static ca.yorku.eecs3311.huntassist.review.DefensiveAction.REVIEW_OUTPUT_ENCODING;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReviewServiceTest {
    private final UUID project = UUID.randomUUID();
    private final UUID endpoint = UUID.randomUUID();
    private final UUID observation = UUID.randomUUID();
    private final EvidenceService evidence = mock(EvidenceService.class);
    private final KnowledgeService knowledge = mock(KnowledgeService.class);
    private final ReviewRepository repository = mock(ReviewRepository.class);

    @Test
    void ranksOnlyExistingActionsAndPreservesTheirEvidence() {
        when(evidence.evidence(project, endpoint)).thenReturn(List.of(observation("text/html", false, false)));
        var service = service((signals, actions) -> {
            assertThat(signals).containsEntry(VulnerabilityModule.Signal.HTML_RESPONSE_OBSERVED, true);
            assertThat(actions).containsExactly(REVIEW_OUTPUT_ENCODING,
                    REVIEW_CONTENT_SECURITY_POLICY, REVIEW_CONTENT_TYPE_OPTIONS);
            return new LocalRanker.Ranking(LocalRanker.Status.MODEL_RANKED,
                    List.of(REVIEW_CONTENT_SECURITY_POLICY, REVIEW_OUTPUT_ENCODING, REVIEW_CONTENT_TYPE_OPTIONS));
        });
        var plan = service.plan(project, endpoint, "xss");
        assertThat(plan.status()).isEqualTo("MODEL_RANKED");
        assertThat(plan.actions()).extracting(ReviewService.Action::code)
                .containsExactly("REVIEW_CONTENT_SECURITY_POLICY", "REVIEW_OUTPUT_ENCODING",
                        "REVIEW_CONTENT_TYPE_OPTIONS");
        assertThat(plan.actions()).allSatisfy(action -> assertThat(action.evidenceIds()).containsExactly(observation));
        verify(repository).saveRun(project, "PLAN", 1, plan);
    }

    @Test
    void rejectsUnsupportedModelActionAndPreservesDeterministicOrder() {
        when(evidence.evidence(project, endpoint)).thenReturn(List.of(observation("text/html", false, true)));
        var service = service((signals, actions) -> new LocalRanker.Ranking(LocalRanker.Status.MODEL_RANKED,
                List.of(REVIEW_CONTENT_SECURITY_POLICY, REVIEW_CONTENT_TYPE_OPTIONS)));
        var plan = service.plan(project, endpoint, "xss");
        assertThat(plan.status()).isEqualTo("MODEL_INVALID");
        assertThat(plan.actions()).extracting(ReviewService.Action::code)
                .containsExactly("REVIEW_OUTPUT_ENCODING", "REVIEW_CONTENT_SECURITY_POLICY");
    }

    @Test
    void neverInvokesTheModelWhenHtmlIsAbsentOrReviewIsClosed() {
        var service = service((signals, actions) -> {
            throw new AssertionError("Model should not run");
        });
        when(evidence.evidence(project, endpoint)).thenReturn(List.of(observation("application/json", false, false)));
        assertThat(service.plan(project, endpoint, "xss").status()).isEqualTo("NOT_APPLICABLE");
        when(evidence.evidence(project, endpoint)).thenReturn(List.of(observation("text/html", false, false)));
        when(repository.coverage(project, endpoint, "xss")).thenReturn(Optional.of(
                new ReviewService.Coverage(endpoint, "xss", "ADDRESSED", "", Instant.now())));
        var plan = service.plan(project, endpoint, "xss");
        assertThat(plan.status()).isEqualTo("COVERAGE_CLOSED");
        assertThat(plan.actions()).isEmpty();
        assertThatThrownBy(() -> service.analyze(project, endpoint, "other")).isInstanceOf(AppException.class);
    }

    @Test
    void defensiveQuestionScopesActionSelectionAndRecordsAudit() {
        when(evidence.evidence(project, endpoint)).thenReturn(List.of(observation("text/html", false, false)));
        var citation = new KnowledgeService.Citation(UUID.randomUUID(), "guide.md", 2, "Policy", "Check CSP");
        var unrelated = new KnowledgeService.Citation(
                UUID.randomUUID(), "guide.md", 2, "MIME and browser behavior", "Serve the expected MIME type");
        when(knowledge.search(project, "content security policy csp")).thenReturn(List.of(unrelated, citation));
        var service = service((signals, actions) -> {
            assertThat(actions).containsExactly(REVIEW_CONTENT_SECURITY_POLICY);
            return new LocalRanker.Ranking(LocalRanker.Status.MODEL_DISABLED, List.of());
        });
        var answer = service.ask(project, endpoint, "xss", "What should I review about CSP?");
        assertThat(answer.analysis().status()).isEqualTo("MODEL_DISABLED");
        assertThat(answer.analysis().actions()).extracting(ReviewService.Action::code)
                .containsExactly("REVIEW_CONTENT_SECURITY_POLICY");
        assertThat(answer.citations()).containsExactly(citation);
        verify(knowledge, never()).search(project, "XSS output encoding content security policy MIME nosniff");
        verify(repository).saveRun(eq(project), eq("QUESTION"), eq(1), any());
        assertThatThrownBy(() -> service.ask(project, endpoint, "unknown", "What about CSP?"))
                .isInstanceOf(AppException.class);
    }

    @Test
    void focusedQuestionWithoutMatchingNotesDoesNotAddUnrelatedCitations() {
        when(evidence.evidence(project, endpoint)).thenReturn(List.of(observation("text/html", false, true)));
        var unrelated = new KnowledgeService.Citation(
                UUID.randomUUID(), "guide.md", 1, "Escaping", "Use output encoding.");
        when(knowledge.search(project, "XSS output encoding content security policy MIME nosniff"))
                .thenReturn(List.of(unrelated));
        var service = service((signals, actions) -> new LocalRanker.Ranking(LocalRanker.Status.MODEL_DISABLED, List.of()));

        assertThat(service.ask(project, endpoint, "What should I know about CSP?").citations()).isEmpty();
        assertThat(service.ask(project, endpoint, "What should we review?").citations()).containsExactly(unrelated);
    }

    private ReviewService service(LocalRanker ranker) {
        return new ReviewService(mock(ProjectService.class), evidence, knowledge,
                new VulnerabilityModuleRegistry(List.of(new XssMitigationModule())), ranker, repository);
    }

    private EvidenceService.Observation observation(String contentType, boolean csp, boolean nosniff) {
        return new EvidenceService.Observation(
                observation, UUID.randomUUID(), 1, 200, contentType, "reference", csp, nosniff, false);
    }
}

package ca.yorku.eecs3311.huntassist.review;

import ca.yorku.eecs3311.huntassist.application.AppException;
import ca.yorku.eecs3311.huntassist.application.ProjectService;
import ca.yorku.eecs3311.huntassist.evidence.EvidenceService;
import ca.yorku.eecs3311.huntassist.review.KnowledgeService.Citation;
import ca.yorku.eecs3311.huntassist.review.VulnerabilityModule.Assessment;
import ca.yorku.eecs3311.huntassist.review.VulnerabilityModule.Candidate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
public class ReviewService {
    private static final String REFERENCE_MODULE = "xss";
    private static final Pattern POLICY_QUESTION = Pattern.compile("\\bcsp\\b|content[ -]security[ -]policy");
    private static final Pattern ENCODING_QUESTION = Pattern.compile("encod|escap|saniti[sz]");
    private static final Pattern MIME_QUESTION = Pattern.compile("mime|sniff|content[ -]type");
    private static final Set<String> CLOSED_STATES = Set.of("ADDRESSED", "DISMISSED");

    public record ModuleInfo(String id, String title, String description) {}

    public record Action(String code, String title, String remediation, List<UUID> evidenceIds) {}

    public record Analysis(UUID endpointId, String module, String status, int observedResponses,
                           List<Action> actions, List<Citation> citations) {}

    public record Plan(UUID runId, UUID endpointId, String module, String status, List<Action> actions,
                       List<Citation> citations, Instant generatedAt) {}

    public record Answer(String question, Analysis analysis, List<Citation> citations) {}

    public record Coverage(UUID endpointId, String module, String state, String note, Instant updatedAt) {}

    public record Run(UUID id, UUID endpointId, String module, String status, int actionCount, Instant generatedAt) {}

    private enum CoverageState {
        TODO, IN_REVIEW, ADDRESSED, DISMISSED
    }

    private enum QuestionTopic {
        POLICY(POLICY_QUESTION, DefensiveAction.REVIEW_CONTENT_SECURITY_POLICY, "content security policy csp"),
        ENCODING(ENCODING_QUESTION, DefensiveAction.REVIEW_OUTPUT_ENCODING, "output encoding escaping sanitizer"),
        MIME(MIME_QUESTION, DefensiveAction.REVIEW_CONTENT_TYPE_OPTIONS, "mime nosniff content type");

        final Pattern pattern;
        final DefensiveAction action;
        final String query;

        QuestionTopic(Pattern pattern, DefensiveAction action, String query) {
            this.pattern = pattern;
            this.action = action;
            this.query = query;
        }

        boolean matches(String text) {
            return pattern.matcher(text.toLowerCase(Locale.ROOT)).find();
        }
    }

    private record Context(UUID projectId, UUID endpointId, String module, int observationCount,
                           Assessment assessment, List<Citation> citations) {}

    private final ProjectService projects;
    private final EvidenceService evidence;
    private final KnowledgeService knowledge;
    private final VulnerabilityModuleRegistry modules;
    private final LocalRanker ranker;
    private final ReviewRepository repository;

    public ReviewService(ProjectService projects, EvidenceService evidence, KnowledgeService knowledge,
                         VulnerabilityModuleRegistry modules, LocalRanker ranker, ReviewRepository repository) {
        this.projects = projects;
        this.evidence = evidence;
        this.knowledge = knowledge;
        this.modules = modules;
        this.ranker = ranker;
        this.repository = repository;
    }

    public List<ModuleInfo> modules() {
        return modules.list();
    }

    public Analysis analyze(UUID projectId, UUID endpointId, String module) {
        Context context = context(projectId, endpointId, module, true);
        String status = context.assessment().applicable() ? "OBSERVATIONS_AVAILABLE" : "NOT_APPLICABLE";
        return analysis(context, status, actions(context.assessment().candidates()), context.citations());
    }

    public Plan plan(UUID projectId, UUID endpointId, String module) {
        Context context = context(projectId, endpointId, module, true);
        return plan(context, "PLAN", context.assessment().candidates(), context.citations());
    }

    public Answer ask(UUID projectId, UUID endpointId, String question) {
        return ask(projectId, endpointId, REFERENCE_MODULE, question);
    }

    public Answer ask(UUID projectId, UUID endpointId, String module, String question) {
        String normalized = question == null ? "" : NoteParser.clean(question).strip();
        if (normalized.isEmpty() || normalized.length() > 300) {
            throw AppException.invalid("Enter a review question (up to 300 characters).");
        }
        Set<QuestionTopic> topics = topics(normalized);
        Context context = context(projectId, endpointId, module, topics.isEmpty());
        List<Candidate> candidates = focus(context.assessment().candidates(), topics);
        List<Citation> citations = topics.isEmpty()
                ? combine(knowledge.search(projectId, normalized), context.citations())
                : focusedCitations(projectId, topics);
        Plan plan = plan(context, "QUESTION", candidates, citations);
        return new Answer(normalized, analysis(context, plan.status(), plan.actions(), citations), citations);
    }

    public List<Coverage> coverage(UUID projectId) {
        projects.require(projectId);
        return repository.coverage(projectId);
    }

    public Coverage recordCoverage(UUID projectId, UUID endpointId, String state, String note) {
        return recordCoverage(projectId, endpointId, REFERENCE_MODULE, state, note);
    }

    public Coverage recordCoverage(UUID projectId, UUID endpointId, String module, String state, String note) {
        String moduleId = modules.require(module).info().id();
        CoverageState next;
        try {
            next = CoverageState.valueOf(state == null ? "" : state.strip().toUpperCase(Locale.ROOT).replace('-', '_'));
        } catch (IllegalArgumentException failure) {
            throw AppException.invalid("Choose TODO, IN_REVIEW, ADDRESSED, or DISMISSED.");
        }
        String comment = note == null ? "" : NoteParser.clean(note).strip();
        if (comment.length() > 400) {
            throw AppException.invalid("Use up to 400 characters for the review note.");
        }
        evidence.evidence(projectId, endpointId);
        Coverage coverage = new Coverage(endpointId, moduleId, next.name(), comment, Instant.now());
        repository.saveCoverage(projectId, coverage);
        return coverage;
    }

    public List<Run> runs(UUID projectId) {
        projects.require(projectId);
        return repository.runs(projectId);
    }

    private Context context(UUID projectId, UUID endpointId, String category, boolean includeModuleCitations) {
        VulnerabilityModule module = modules.require(category);
        var observations = evidence.evidence(projectId, endpointId);
        Assessment assessment = module.assess(observations);
        List<Citation> citations = assessment.applicable() && includeModuleCitations
                ? knowledge.search(projectId, module.knowledgeQuery()) : List.of();
        return new Context(projectId, endpointId, module.info().id(), observations.size(), assessment, citations);
    }

    private Plan plan(Context context, String operation, List<Candidate> candidates, List<Citation> citations) {
        String status;
        List<Action> ordered = List.of();
        if (!context.assessment().applicable()) {
            status = "NOT_APPLICABLE";
        } else if (candidates.isEmpty()) {
            status = "NO_MATCHING_ACTIONS";
        } else if (isClosed(context)) {
            status = "COVERAGE_CLOSED";
        } else {
            List<DefensiveAction> allowed = candidates.stream().map(Candidate::action).toList();
            LocalRanker.Ranking ranking = ranker.rank(context.assessment().signals(), allowed);
            status = ranking.status().name();
            ordered = actions(candidates);
            if (ranking.status() == LocalRanker.Status.MODEL_RANKED) {
                if (ranking.order().size() == allowed.size()
                        && new HashSet<>(ranking.order()).equals(new HashSet<>(allowed))) {
                    var actionsByCode = new LinkedHashMap<DefensiveAction, Candidate>();
                    candidates.forEach(candidate -> actionsByCode.put(candidate.action(), candidate));
                    ordered = actions(ranking.order().stream().map(actionsByCode::get).toList());
                } else {
                    status = LocalRanker.Status.MODEL_INVALID.name();
                }
            }
        }
        Plan plan = new Plan(UUID.randomUUID(), context.endpointId(), context.module(),
                status, ordered, citations, Instant.now());
        repository.saveRun(context.projectId(), operation, context.observationCount(), plan);
        return plan;
    }

    private boolean isClosed(Context context) {
        return repository.coverage(context.projectId(), context.endpointId(), context.module())
                .map(coverage -> CLOSED_STATES.contains(coverage.state()))
                .orElse(false);
    }

    private static Analysis analysis(Context context, String status, List<Action> actions, List<Citation> citations) {
        return new Analysis(context.endpointId(), context.module(), status, context.observationCount(), actions, citations);
    }

    private static List<Action> actions(List<Candidate> candidates) {
        return candidates.stream().map(candidate -> {
            DefensiveAction action = candidate.action();
            return new Action(action.name(), action.title, action.remediation, candidate.evidenceIds());
        }).toList();
    }

    private static Set<QuestionTopic> topics(String question) {
        Set<QuestionTopic> result = EnumSet.noneOf(QuestionTopic.class);
        for (QuestionTopic topic : QuestionTopic.values()) {
            if (topic.matches(question)) {
                result.add(topic);
            }
        }
        return result;
    }

    private static List<Candidate> focus(List<Candidate> candidates, Set<QuestionTopic> topics) {
        if (topics.isEmpty()) {
            return candidates;
        }
        return candidates.stream().filter(candidate ->
                topics.stream().anyMatch(topic -> topic.action == candidate.action())).toList();
    }

    private List<Citation> focusedCitations(UUID projectId, Set<QuestionTopic> topics) {
        var matches = new LinkedHashMap<UUID, Citation>();
        for (QuestionTopic topic : topics) {
            for (Citation citation : knowledge.search(projectId, topic.query)) {
                if (topic.matches(citation.section()) || topic.matches(citation.excerpt())) {
                    matches.putIfAbsent(citation.id(), citation);
                }
            }
        }
        return matches.values().stream().limit(8).toList();
    }

    private static List<Citation> combine(List<Citation> first, List<Citation> second) {
        var merged = new LinkedHashMap<UUID, Citation>();
        first.forEach(citation -> merged.putIfAbsent(citation.id(), citation));
        second.forEach(citation -> merged.putIfAbsent(citation.id(), citation));
        return merged.values().stream().limit(8).toList();
    }
}

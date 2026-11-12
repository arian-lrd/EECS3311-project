package ca.yorku.eecs3311.huntassist.presentation.web;

import ca.yorku.eecs3311.huntassist.application.AppException;
import ca.yorku.eecs3311.huntassist.application.ProjectService;
import ca.yorku.eecs3311.huntassist.evidence.EvidenceService;
import ca.yorku.eecs3311.huntassist.review.KnowledgeService;
import ca.yorku.eecs3311.huntassist.review.ReviewService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Controller
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
class WorkbenchController {
    private static final int INVENTORY_PAGE_SIZE = 100;
    private static final int SELECT_SIZE = 250;
    private static final List<String> STATES = List.of("TODO", "IN_REVIEW", "ADDRESSED", "DISMISSED");

    private final ProjectService projects;
    private final EvidenceService evidence;
    private final KnowledgeService knowledge;
    private final ReviewService review;

    WorkbenchController(ProjectService projects, EvidenceService evidence, KnowledgeService knowledge,
                        ReviewService review) {
        this.projects = projects;
        this.evidence = evidence;
        this.knowledge = knowledge;
        this.review = review;
    }

    @ModelAttribute("csrfToken")
    String csrfToken(HttpServletRequest request) {
        return (String) request.getAttribute("csrfToken");
    }

    @GetMapping("/")
    String index(Model model) {
        model.addAttribute("projects", projects.list());
        return "projects";
    }

    @PostMapping("/projects")
    String create(@RequestParam String name, @RequestParam String includes,
                  @RequestParam(defaultValue = "") String excludes,
                  @RequestParam(defaultValue = "") String restrictions,
                  RedirectAttributes redirect) {
        var project = projects.create(name, scopeLines(includes), scopeLines(excludes), restrictions);
        redirect.addFlashAttribute("notice", "Workspace created.");
        return workspaceRoute(project.id(), "overview");
    }

    @GetMapping("/projects/{projectId}")
    String open(@PathVariable UUID projectId) {
        return workspaceRoute(projectId, "overview");
    }

    @GetMapping("/projects/{projectId}/{view}")
    String workspace(@PathVariable UUID projectId, @PathVariable String view,
                     @RequestParam Map<String, String> parameters, Model model) {
        var project = projects.require(projectId);
        model.addAttribute("project", project);
        model.addAttribute("view", view);
        switch (view) {
            case "overview" -> overview(projectId, parameters, model);
            case "traffic" -> model.addAttribute("imports", evidence.imports(projectId));
            case "inventory" -> inventory(projectId, parameters, model);
            case "compare" -> compare(projectId, parameters, model);
            case "knowledge" -> knowledge(projectId, parameters, model);
            case "review" -> {
                loadEndpointChoices(projectId, parameters, model);
                model.addAttribute("modules", review.modules());
                model.addAttribute("selectedModule", parameters.getOrDefault("module", "xss"));
                model.addAttribute("runs", review.runs(projectId));
            }
            case "coverage" -> {
                loadEndpointChoices(projectId, parameters, model);
                model.addAttribute("modules", review.modules());
                model.addAttribute("states", STATES);
                model.addAttribute("coverage", review.coverage(projectId));
            }
            default -> throw AppException.notFound("Unknown workspace section.");
        }
        return "workspace";
    }

    private void overview(UUID projectId, Map<String, String> parameters, Model model) {
        model.addAttribute("endpointCount", evidence.inventory(projectId).size());
        model.addAttribute("importCount", evidence.imports(projectId).size());
        model.addAttribute("noteCount", knowledge.list(projectId).size());
        model.addAttribute("coverageCount", review.coverage(projectId).size());
        String host = parameters.getOrDefault("host", "").strip();
        if (!host.isBlank()) {
            String decision = switch (projects.classify(projectId, host)) {
                case IN_SCOPE -> "In scope";
                case OUT_OF_SCOPE -> "Out of scope";
                case UNRESOLVED -> "Unresolved";
            };
            model.addAttribute("scopeDecision", decision);
            model.addAttribute("checkedHost", host);
        }
    }

    private void inventory(UUID projectId, Map<String, String> parameters, Model model) {
        String search = parameters.getOrDefault("q", "").strip();
        String normalized = search.toLowerCase(Locale.ROOT);
        List<EvidenceService.Endpoint> matches = evidence.inventory(projectId).stream()
                .filter(endpoint -> (endpoint.method() + " " + endpoint.host() + endpoint.path())
                        .toLowerCase(Locale.ROOT).contains(normalized)).toList();
        int page = pageNumber(parameters.get("page"));
        int pageCount = Math.max(1, (matches.size() + INVENTORY_PAGE_SIZE - 1) / INVENTORY_PAGE_SIZE);
        page = Math.min(page, pageCount);
        int offset = (page - 1) * INVENTORY_PAGE_SIZE;
        model.addAttribute("endpoints", matches.subList(offset, Math.min(offset + INVENTORY_PAGE_SIZE,
                matches.size())));
        model.addAttribute("matchCount", matches.size());
        model.addAttribute("q", search);
        model.addAttribute("page", page);
        model.addAttribute("pageCount", pageCount);
        UUID endpointId = endpointId(parameters);
        if (endpointId != null) {
            var observations = evidence.evidence(projectId, endpointId);
            model.addAttribute("selectedEndpoint", endpointId);
            model.addAttribute("observations", observations.stream().limit(250).toList());
            model.addAttribute("observationCount", observations.size());
        }
    }

    private void compare(UUID projectId, Map<String, String> parameters, Model model) {
        model.addAttribute("profiles", evidence.imports(projectId).stream()
                .map(EvidenceService.ImportResult::profile).distinct().sorted().toList());
        String first = parameters.getOrDefault("first", "");
        String second = parameters.getOrDefault("second", "");
        model.addAttribute("first", first);
        model.addAttribute("second", second);
        if (!first.isBlank() && !second.isBlank()) {
            model.addAttribute("comparison", evidence.compare(projectId, first, second));
        }
    }

    private void knowledge(UUID projectId, Map<String, String> parameters, Model model) {
        String query = parameters.getOrDefault("q", "").strip();
        model.addAttribute("notes", knowledge.list(projectId));
        model.addAttribute("q", query);
        if (!query.isBlank()) {
            model.addAttribute("citations", knowledge.search(projectId, query));
        }
    }

    private void loadEndpointChoices(UUID projectId, Map<String, String> parameters, Model model) {
        UUID selected = endpointId(parameters);
        List<EvidenceService.Endpoint> endpoints = evidence.inventory(projectId);
        var visible = new ArrayList<>(endpoints.subList(0, Math.min(endpoints.size(), SELECT_SIZE)));
        if (selected != null && visible.stream().noneMatch(endpoint -> endpoint.id().equals(selected))) {
            endpoints.stream().filter(endpoint -> endpoint.id().equals(selected)).findFirst().ifPresent(visible::add);
        }
        model.addAttribute("endpoints", visible);
        model.addAttribute("selectedEndpoint", selected);
        model.addAttribute("endpointCount", endpoints.size());
    }

    @PostMapping("/projects/{projectId}/traffic")
    String importTraffic(@PathVariable UUID projectId, @RequestParam MultipartFile file,
                         @RequestParam(defaultValue = "default") String profile, RedirectAttributes redirect) {
        requireFile(file);
        try (var source = file.getInputStream()) {
            var result = evidence.importTraffic(projectId, source, profile);
            redirect.addFlashAttribute("notice", result.duplicate() ? "This capture is already imported."
                    : "Import saved. Retained " + result.accepted() + " in-scope exchanges.");
            return workspaceRoute(projectId, "traffic");
        } catch (IOException exception) {
            throw AppException.invalid("Could not read the uploaded capture.");
        }
    }

    @PostMapping("/projects/{projectId}/knowledge")
    String importNote(@PathVariable UUID projectId, @RequestParam MultipartFile file,
                      RedirectAttributes redirect) {
        requireFile(file);
        try (var source = file.getInputStream()) {
            var note = knowledge.importNote(projectId, Objects.requireNonNullElse(file.getOriginalFilename(), ""),
                    source);
            redirect.addFlashAttribute("notice", note.unchanged() ? "This note version is already indexed."
                    : "Indexed " + note.sectionCount() + " sections from version " + note.version() + ".");
            return workspaceRoute(projectId, "knowledge");
        } catch (IOException exception) {
            throw AppException.invalid("Could not read the uploaded note.");
        }
    }

    @PostMapping("/projects/{projectId}/review")
    String runReview(@PathVariable UUID projectId, @RequestParam UUID endpoint,
                     @RequestParam(defaultValue = "xss") String module, @RequestParam String operation,
                     @RequestParam(defaultValue = "") String question, RedirectAttributes redirect) {
        switch (operation) {
            case "analyze" -> redirect.addFlashAttribute("analysis", review.analyze(projectId, endpoint, module));
            case "plan" -> redirect.addFlashAttribute("plan", review.plan(projectId, endpoint, module));
            case "ask" -> redirect.addFlashAttribute("answer", review.ask(projectId, endpoint, module, question));
            default -> throw AppException.invalid("Choose a supported review operation.");
        }
        redirect.addAttribute("endpoint", endpoint);
        redirect.addAttribute("module", module);
        return workspaceRoute(projectId, "review");
    }

    @PostMapping("/projects/{projectId}/coverage")
    String saveCoverage(@PathVariable UUID projectId, @RequestParam UUID endpoint, @RequestParam String module,
                        @RequestParam String state, @RequestParam(defaultValue = "") String note,
                        RedirectAttributes redirect) {
        review.recordCoverage(projectId, endpoint, module, state, note);
        redirect.addFlashAttribute("notice", "Coverage saved.");
        redirect.addAttribute("endpoint", endpoint);
        return workspaceRoute(projectId, "coverage");
    }

    @ExceptionHandler(AppException.class)
    String applicationError(AppException exception, HttpServletResponse response, Model model) {
        int status = switch (exception.code()) {
            case INVALID -> 400;
            case NOT_FOUND -> 404;
            case REFUSED -> 403;
            case UNAVAILABLE -> 503;
            case CONFLICT, CANCELLED -> 409;
            case FAILED, INTERNAL -> 500;
        };
        response.setStatus(status);
        model.addAttribute("problem", exception.getMessage());
        return "problem";
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    String invalidParameter(HttpServletResponse response, Model model) {
        return applicationError(AppException.invalid("An identifier is missing or invalid."), response, model);
    }

    private static List<String> scopeLines(String value) {
        return Arrays.stream(value.split("[\\r\\n]+")).map(String::strip)
                .filter(line -> !line.isEmpty()).toList();
    }

    private static int pageNumber(String value) {
        if (value == null) {
            return 1;
        }
        try {
            return Math.max(1, Integer.parseInt(value));
        } catch (NumberFormatException exception) {
            throw AppException.invalid("Page must be a whole number.");
        }
    }

    private static UUID endpointId(Map<String, String> parameters) {
        String value = parameters.getOrDefault("endpoint", "");
        if (value.isBlank()) {
            return null;
        }
        try {
            UUID id = UUID.fromString(value);
            if (id.toString().equalsIgnoreCase(value)) {
                return id;
            }
        } catch (IllegalArgumentException ignored) {
            // Report the same safe message for malformed and non-canonical identifiers.
        }
        throw AppException.invalid("Endpoint identifier is invalid.");
    }

    private static void requireFile(MultipartFile file) {
        if (file.isEmpty()) {
            throw AppException.invalid("Select a non-empty file.");
        }
    }

    private static String workspaceRoute(UUID projectId, String view) {
        return "redirect:/projects/" + projectId + "/" + view;
    }
}

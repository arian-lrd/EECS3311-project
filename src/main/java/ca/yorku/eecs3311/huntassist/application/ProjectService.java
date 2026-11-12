package ca.yorku.eecs3311.huntassist.application;

import ca.yorku.eecs3311.huntassist.domain.project.ProgramScope;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class ProjectService {
    public enum ScopeDecision { IN_SCOPE, OUT_OF_SCOPE, UNRESOLVED }
    public record Project(UUID id, String name, List<String> includes, List<String> excludes,
                          String restrictions, Instant createdAt) {}

    private final ProjectRepository projects;

    public ProjectService(ProjectRepository projects) { this.projects = projects; }

    @Transactional
    public Project create(String name, List<String> includes, List<String> excludes, String restrictions) {
        if (name == null || name.isBlank() || name.strip().length() > 120 ||
                name.codePoints().anyMatch(Character::isISOControl)) {
            throw AppException.invalid("Enter a project name of 1 to 120 characters without control characters.");
        }
        String normalizedRestrictions = restrictions == null ? "" : restrictions.strip();
        if (normalizedRestrictions.length() > 2000 ||
                normalizedRestrictions.codePoints().anyMatch(c -> Character.isISOControl(c) && c != '\n' && c != '\r' && c != '\t')) {
            throw AppException.invalid("Enter up to 2,000 characters of restrictions.");
        }
        final ProgramScope scope;
        try {
            scope = new ProgramScope(includes, excludes);
        } catch (IllegalArgumentException e) {
            throw AppException.invalid(e.getMessage());
        }
        Project project = new Project(UUID.randomUUID(), name.strip(), scope.includes(), scope.excludes(),
                normalizedRestrictions, Instant.now());
        projects.insert(project);
        return project;
    }

    public List<Project> list() { return projects.list(); }

    public Project require(UUID id) {
        if (id == null) {
            throw AppException.invalid("A project ID is required.");
        }
        return projects.find(id).orElseThrow(() -> AppException.notFound("Project not found."));
    }

    public ScopeDecision classify(UUID id, String host) {
        return ScopeDecision.valueOf(scope(id).classify(host).name());
    }

    public ProgramScope scope(UUID id) {
        Project project = require(id);
        return new ProgramScope(project.includes(), project.excludes());
    }

    public boolean isInScope(UUID id, String host) {
        return classify(id, host) == ScopeDecision.IN_SCOPE;
    }
}

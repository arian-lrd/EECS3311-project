package ca.yorku.eecs3311.huntassist.application;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProjectRepository {
    void insert(ProjectService.Project project);
    Optional<ProjectService.Project> find(UUID id);
    List<ProjectService.Project> list();
}

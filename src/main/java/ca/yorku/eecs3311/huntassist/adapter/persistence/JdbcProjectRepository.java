package ca.yorku.eecs3311.huntassist.adapter.persistence;

import ca.yorku.eecs3311.huntassist.application.ProjectRepository;
import ca.yorku.eecs3311.huntassist.application.ProjectService.Project;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JdbcProjectRepository implements ProjectRepository {
    private record Row(UUID id, String name, String restrictions, OffsetDateTime createdAt) {}

    private final JdbcTemplate jdbc;

    public JdbcProjectRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public void insert(Project project) {
        jdbc.update("INSERT INTO projects (id, name, restrictions, created_at) VALUES (?, ?, ?, ?)",
                project.id(), project.name(), project.restrictions(), project.createdAt().atOffset(ZoneOffset.UTC));
        insertRules(project.id(), project.includes(), false);
        insertRules(project.id(), project.excludes(), true);
    }

    @Override
    public Optional<Project> find(UUID id) {
        return jdbc.query("SELECT * FROM projects WHERE id = ?", (rs, n) -> read(rs), id)
                .stream().findFirst().map(this::withScope);
    }

    @Override
    public List<Project> list() {
        return jdbc.query("SELECT * FROM projects ORDER BY created_at DESC, id",
                (rs, n) -> read(rs)).stream().map(this::withScope).toList();
    }

    private static Row read(ResultSet rs) throws SQLException {
        return new Row(rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("restrictions"),
                rs.getObject("created_at", OffsetDateTime.class));
    }

    private Project withScope(Row row) {
        return new Project(row.id, row.name, rules(row.id, false), rules(row.id, true),
                row.restrictions, row.createdAt.toInstant());
    }

    private void insertRules(UUID id, List<String> rules, boolean excluded) {
        for (String rule : rules) {
            jdbc.update("INSERT INTO project_scope (project_id, excluded, host_rule) VALUES (?, ?, ?)",
                    id, excluded, rule);
        }
    }

    private List<String> rules(UUID id, boolean excluded) {
        return jdbc.queryForList("SELECT host_rule FROM project_scope WHERE project_id = ? AND excluded = ? ORDER BY host_rule",
                String.class, id, excluded);
    }
}

package ca.yorku.eecs3311.huntassist.review;

import ca.yorku.eecs3311.huntassist.review.ReviewService.Coverage;
import ca.yorku.eecs3311.huntassist.review.ReviewService.Plan;
import ca.yorku.eecs3311.huntassist.review.ReviewService.Run;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
class ReviewRepository {
    private static final RowMapper<Coverage> COVERAGE_MAPPER = (row, index) -> new Coverage(
            row.getObject("endpoint_id", UUID.class), row.getString("module_id"),
            row.getString("state"), row.getString("note"),
            row.getObject("updated_at", OffsetDateTime.class).toInstant());
    private final JdbcTemplate database;

    ReviewRepository(JdbcTemplate database) {
        this.database = database;
    }

    @Transactional
    public void saveRun(UUID projectId, String operation, int observedResponses, Plan plan) {
        database.update("""
                insert into review_run
                (id, project_id, endpoint_id, module_id, operation, status, observed_responses, action_count, generated_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, plan.runId(), projectId, plan.endpointId(), plan.module(), operation, plan.status(),
                observedResponses, plan.actions().size(), plan.generatedAt().atOffset(ZoneOffset.UTC));
        for (int position = 0; position < plan.actions().size(); position++) {
            var action = plan.actions().get(position);
            database.update("""
                    insert into review_run_action (run_id, ordinal_no, action_code) values (?, ?, ?)
                    """, plan.runId(), position, action.code());
            for (UUID evidenceId : action.evidenceIds()) {
                database.update("""
                        insert into review_run_evidence (run_id, action_position, evidence_id) values (?, ?, ?)
                        """, plan.runId(), position, evidenceId);
            }
        }
        for (var citation : plan.citations()) {
            database.update("""
                    insert into review_run_citation (run_id, section_id) values (?, ?)
                    """, plan.runId(), citation.id());
        }
    }

    List<Run> runs(UUID projectId) {
        return database.query("""
                select id, endpoint_id, module_id, status, action_count, generated_at
                from review_run where project_id = ? order by generated_at desc, id limit 100
                """, (row, index) -> new Run(row.getObject("id", UUID.class),
                row.getObject("endpoint_id", UUID.class), row.getString("module_id"),
                row.getString("status"), row.getInt("action_count"),
                row.getObject("generated_at", OffsetDateTime.class).toInstant()), projectId);
    }

    Optional<Coverage> coverage(UUID projectId, UUID endpointId, String module) {
        return database.query("""
                select endpoint_id, module_id, state, note, updated_at from review_coverage
                where project_id = ? and endpoint_id = ? and module_id = ?
                """, COVERAGE_MAPPER, projectId, endpointId, module).stream().findFirst();
    }

    List<Coverage> coverage(UUID projectId) {
        return database.query("""
                select endpoint_id, module_id, state, note, updated_at from review_coverage
                where project_id = ? order by updated_at desc, endpoint_id, module_id
                """, COVERAGE_MAPPER, projectId);
    }

    @Transactional
    public void saveCoverage(UUID projectId, Coverage coverage) {
        OffsetDateTime timestamp = coverage.updatedAt().atOffset(ZoneOffset.UTC);
        database.update("""
                merge into review_coverage (project_id, endpoint_id, module_id, state, note, updated_at)
                key (project_id, endpoint_id, module_id) values (?, ?, ?, ?, ?, ?)
                """, projectId, coverage.endpointId(), coverage.module(), coverage.state(), coverage.note(), timestamp);
        database.update("""
                insert into review_coverage_history (id, project_id, endpoint_id, module_id, state, note, updated_at)
                values (?, ?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), projectId, coverage.endpointId(), coverage.module(),
                coverage.state(), coverage.note(), timestamp);
    }
}

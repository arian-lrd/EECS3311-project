package ca.yorku.eecs3311.huntassist.review;

import ca.yorku.eecs3311.huntassist.application.AppException;
import ca.yorku.eecs3311.huntassist.application.ProjectService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class KnowledgeServiceTest {
    private final UUID firstProject = UUID.randomUUID();
    private final UUID secondProject = UUID.randomUUID();
    private EmbeddedDatabase database;
    private KnowledgeService knowledge;

    @BeforeEach
    void setup() {
        database = new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2)
                .generateUniqueName(true)
                .build();
        JdbcTemplate sql = new JdbcTemplate(database);
        sql.execute("create table projects(id uuid primary key)");
        sql.execute("create table evidence_endpoints(id uuid primary key)");
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V3__review.sql")).execute(database);
        sql.update("insert into projects values (?)", firstProject);
        sql.update("insert into projects values (?)", secondProject);
        knowledge = new KnowledgeService(sql, mock(ProjectService.class));
    }

    @AfterEach
    void close() {
        database.shutdown();
    }

    @Test
    void latestVersionIsSearchableAndOldVersionsRemainInHistory() {
        var original = knowledge.importNote(firstProject, "notes.md",
                text("# Content Security Policy\noldcontrols were documented."));
        var duplicate = knowledge.importNote(firstProject, "notes.md",
                text("# Content Security Policy\noldcontrols were documented."));
        var revision = knowledge.importNote(firstProject, "notes.md",
                text("# Content Security Policy\nUse a restrictive policy with trusted script sources."));

        assertThat(duplicate.unchanged()).isTrue();
        assertThat(duplicate.id()).isEqualTo(original.id());
        assertThat(revision.version()).isEqualTo(2);
        assertThat(revision.sha256()).isNotEqualTo(original.sha256());
        assertThat(knowledge.list(firstProject)).extracting(KnowledgeService.Note::version)
                .containsExactly(2, 1);
        assertThat(knowledge.search(firstProject, "oldcontrols")).isEmpty();
        assertThat(knowledge.search(firstProject, "content security policy")).singleElement()
                .satisfies(citation -> {
                    assertThat(citation.version()).isEqualTo(2);
                    assertThat(citation.source()).isEqualTo("notes.md");
                    assertThat(citation.section()).isEqualTo("Content Security Policy");
                    assertThat(citation.excerpt()).contains("restrictive policy");
                });
        assertThat(knowledge.search(secondProject, "content security policy")).isEmpty();
    }

    @Test
    void htmlIsIndexedAsProseAndHiddenContentIsExcluded() {
        knowledge.importNote(firstProject, "export.html",
                text("<h2>Escaping</h2><script>hiddenword()</script><p>Use a sanitizer &amp; review rendered contexts.</p>"));
        assertThat(knowledge.search(firstProject, "hiddenword")).isEmpty();
        assertThat(knowledge.search(firstProject, "escaping sanitizer")).singleElement()
                .satisfies(citation -> {
                    assertThat(citation.section()).isEqualTo("Escaping");
                    assertThat(citation.excerpt()).contains("sanitizer & review");
                });
    }

    @Test
    void acceptsTextAndRejectsUnsupportedOrOverLimitDocuments() {
        assertThat(knowledge.importNote(firstProject, "guide.txt", text("Defensive review")).sectionCount())
                .isEqualTo(1);
        assertThatThrownBy(() -> knowledge.importNote(firstProject, "data.zip", text("Defensive review")))
                .isInstanceOf(AppException.class);
        assertThatThrownBy(() -> knowledge.importNote(firstProject, "huge.txt",
                new ByteArrayInputStream(new byte[262_145]))).isInstanceOf(AppException.class);
        assertThat(knowledge.list(firstProject)).hasSize(1);
    }

    private static ByteArrayInputStream text(String content) {
        return new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
    }
}

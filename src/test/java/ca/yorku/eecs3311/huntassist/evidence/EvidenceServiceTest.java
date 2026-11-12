package ca.yorku.eecs3311.huntassist.evidence;

import ca.yorku.eecs3311.huntassist.application.AppException;
import ca.yorku.eecs3311.huntassist.application.ProjectService;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "spring.datasource.url=jdbc:h2:mem:evidence-tests;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa"
})
class EvidenceServiceTest {
    @Autowired ProjectService projects;
    @Autowired EvidenceService evidence;

    @Test
    void importsBothFormatsAndKeepsOnlyScopedObservations() throws IOException {
        UUID projectId = project();
        EvidenceService.ImportResult guest = fixture(projectId, "guest.xml", "guest");
        EvidenceService.ImportResult member = fixture(projectId, "member.json", "member");

        assertThat(guest).satisfies(result -> {
            assertThat(result.accepted()).isEqualTo(2);
            assertThat(result.outOfScope()).isEqualTo(1);
            assertThat(result.invalid()).isEqualTo(1);
        });
        assertThat(member.accepted()).isEqualTo(2);
        List<EvidenceService.Endpoint> inventory = evidence.inventory(projectId);
        assertThat(inventory).extracting(EvidenceService.Endpoint::path)
                .containsExactly("/accounts/{value}/settings", "/catalog");
        assertThat(inventory).extracting(EvidenceService.Endpoint::host).containsOnly("shop.example.test");

        EvidenceService.Endpoint catalog = inventory.getLast();
        assertThat(catalog.observations()).isEqualTo(2);
        List<EvidenceService.Observation> observations = evidence.evidence(projectId, catalog.id());
        assertThat(observations).extracting(EvidenceService.Observation::importId)
                .containsExactlyInAnyOrder(guest.id(), member.id());
        assertThat(observations).extracting(EvidenceService.Observation::contentType).containsOnly("text/html");
        assertThat(observations).filteredOn(item -> item.profile().equals("guest")).singleElement().satisfies(item -> {
            assertThat(item.sourceIndex()).isEqualTo(1);
            assertThat(item.contentTypeOptions()).isTrue();
            assertThat(item.contentSecurityPolicy()).isFalse();
        });
        assertThat(observations).filteredOn(item -> item.profile().equals("member")).singleElement().satisfies(item -> {
            assertThat(item.contentSecurityPolicy()).isTrue();
            assertThat(item.frameOptions()).isTrue();
        });
        assertThat(inventory.toString() + observations).doesNotContain("fixture-session-only", "fixture-cookie-only",
                "fixture-fragment", "default-src", "fixtureperson", "anotherperson");
        assertThat(evidence.imports(projectId)).hasSize(2);
    }

    @Test
    void reimportingTheSameFileAndProfileReturnsOriginalReceipt() throws IOException {
        UUID projectId = project();
        EvidenceService.ImportResult first = fixture(projectId, "guest.xml", " guest ");
        EvidenceService.ImportResult repeated = fixture(projectId, "guest.xml", "guest");
        EvidenceService.ImportResult otherProfile = fixture(projectId, "guest.xml", "member");

        assertThat(repeated.duplicate()).isTrue();
        assertThat(repeated.id()).isEqualTo(first.id());
        assertThat(repeated.importedAt()).isEqualTo(first.importedAt());
        assertThat(otherProfile.duplicate()).isFalse();
        assertThat(evidence.imports(projectId)).hasSize(2);
        assertThat(evidence.inventory(projectId)).allSatisfy(endpoint -> assertThat(endpoint.observations()).isEqualTo(2));
    }

    @Test
    void comparesOnlyStatusCodesAndCountsForTheRequestedProfiles() throws IOException {
        UUID projectId = project();
        fixture(projectId, "guest.xml", "guest");
        fixture(projectId, "member.json", "member");

        assertThat(evidence.compare(projectId, "guest", "member")).filteredOn(row -> row.path().startsWith("/accounts"))
                .singleElement().satisfies(row -> {
                    assertThat(row.firstCount()).isEqualTo(1);
                    assertThat(row.secondCount()).isEqualTo(1);
                    assertThat(row.firstStatuses()).containsExactly(403);
                    assertThat(row.secondStatuses()).containsExactly(200);
                });
        assertThatExceptionOfType(AppException.class).isThrownBy(() -> evidence.compare(projectId, "guest", "guest"))
                .satisfies(failure -> assertThat(failure.code()).isEqualTo(AppException.Code.INVALID));
        assertThatExceptionOfType(AppException.class).isThrownBy(() -> evidence.compare(projectId, "guest", "missing"))
                .satisfies(failure -> assertThat(failure.code()).isEqualTo(AppException.Code.NOT_FOUND));
    }

    @Test
    void rejectsMalformedFilesAtomicallyAndKeepsProjectsIsolated() throws IOException {
        UUID first = project();
        UUID second = project();
        fixture(first, "guest.xml", "guest");
        UUID endpoint = evidence.inventory(first).getFirst().id();

        assertThatExceptionOfType(AppException.class).isThrownBy(() -> evidence.evidence(second, endpoint))
                .satisfies(failure -> assertThat(failure.code()).isEqualTo(AppException.Code.NOT_FOUND));
        assertThatExceptionOfType(AppException.class).isThrownBy(() -> evidence.importTraffic(second, bytes("""
                <items><item><url>https://shop.example.test/catalog</url><method>GET</method><status>200</status></item>
                """), null)).satisfies(failure -> assertThat(failure.code()).isEqualTo(AppException.Code.INVALID));
        assertThat(evidence.imports(second)).isEmpty();
        assertThat(evidence.inventory(second)).isEmpty();
    }

    @Test
    void recordsExcludedHostsOnlyInTheImportCount() throws IOException {
        UUID projectId = projects.create("Excluded host demo", List.of("*.example.test"),
                List.of("shop.example.test"), "").id();

        EvidenceService.ImportResult receipt = fixture(projectId, "guest.xml", "guest");
        assertThat(receipt.accepted()).isEqualTo(1);
        assertThat(receipt.outOfScope()).isEqualTo(2);
        assertThat(evidence.inventory(projectId)).extracting(EvidenceService.Endpoint::host)
                .containsExactly("external.example.test");
    }

    private UUID project() {
        return projects.create("Synthetic capture review", List.of("shop.example.test"), List.of(), "").id();
    }

    private EvidenceService.ImportResult fixture(UUID projectId, String name, String profile) throws IOException {
        try (InputStream source = Files.newInputStream(Path.of("samples", "traffic", name))) {
            return evidence.importTraffic(projectId, source, profile);
        }
    }

    private static InputStream bytes(String source) {
        return new ByteArrayInputStream(source.getBytes(StandardCharsets.UTF_8));
    }
}

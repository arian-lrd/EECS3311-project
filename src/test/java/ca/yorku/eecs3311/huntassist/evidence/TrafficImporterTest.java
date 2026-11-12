package ca.yorku.eecs3311.huntassist.evidence;

import ca.yorku.eecs3311.huntassist.application.AppException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

class TrafficImporterTest {
    @Test
    void readsBase64ResponseHeaderPresenceAndIgnoresBodyText() {
        String response = "HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nX-Frame-Options: DENY\r\n\r\n"
                + "Content-Security-Policy: Sample body text";
        String base64 = Base64.getEncoder().encodeToString(bytes(response));
        String xml = """
                <items><item>
                    <url>https://shop.example.test/catalog</url><method>GET</method><status>200</status>
                    <response base64="true">%s</response>
                </item></items>
                """.formatted(base64);

        assertThat(new BurpXmlTrafficImporter().parse(bytes(xml))).singleElement().satisfies(exchange -> {
            assertThat(exchange.contentType()).isEqualTo("text/html");
            assertThat(exchange.headers().frameOptions()).isTrue();
            assertThat(exchange.headers().contentSecurityPolicy()).isFalse();
        });
    }

    @Test
    void rejectsExternalDocumentTypeDeclarations() {
        String xml = "<!DOCTYPE items SYSTEM 'urn:fixture:external-schema'><items/>";
        assertThatExceptionOfType(AppException.class).isThrownBy(() -> new BurpXmlTrafficImporter().parse(bytes(xml)))
                .satisfies(failure -> assertThat(failure.code()).isEqualTo(AppException.Code.INVALID));
    }

    @Test
    void rejectsAmbiguousOrIncompleteHarDocuments() {
        HarTrafficImporter importer = new HarTrafficImporter();
        assertThatExceptionOfType(AppException.class)
                .isThrownBy(() -> importer.parse(bytes("{\"log\": {\"entries\": [], \"entries\": []}}")));
        assertThatExceptionOfType(AppException.class)
                .isThrownBy(() -> importer.parse(bytes("{\"log\": {\"entries\": []}} {}")));
        assertThatExceptionOfType(AppException.class)
                .isThrownBy(() -> importer.parse(bytes("{\"log\": {}}")));
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }
}

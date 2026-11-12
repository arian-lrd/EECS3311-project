package ca.yorku.eecs3311.huntassist.review;

import ca.yorku.eecs3311.huntassist.application.AppException;
import ca.yorku.eecs3311.huntassist.application.ProjectService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
public class KnowledgeService {
    private static final int MAX_NOTE_BYTES = 256 * 1024;
    private static final int MAX_QUERY_CHARS = 300;
    private static final int MAX_DOCUMENTS_PER_PROJECT = 512;
    private static final int MAX_QUERY_TOKENS = 24;
    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{N}]{2,48}");
    private static final Set<String> STOP_WORDS = Set.of(
            "the", "for", "and", "are", "what", "which", "where", "how", "does", "with",
            "this", "that", "from", "when", "would", "could", "you", "can", "should", "about",
            "endpoint", "my", "your", "of", "to", "is", "in", "do", "on");
    private static final RowMapper<Note> NOTE_MAPPER = (rs, index) -> new Note(
            rs.getObject("id", UUID.class), rs.getString("source"), rs.getInt("version"),
            rs.getString("sha256"), rs.getObject("imported_at", OffsetDateTime.class).toInstant(),
            rs.getInt("section_count"), false);

    public record Note(UUID id, String source, int version, String sha256, Instant importedAt,
                       int sectionCount, boolean unchanged) {}

    public record Citation(UUID id, String source, int version, String section, String excerpt) {}

    private final JdbcTemplate database;
    private final ProjectService projects;
    private final NoteParser parser = new NoteParser();

    public KnowledgeService(JdbcTemplate database, ProjectService projects) {
        this.database = database;
        this.projects = projects;
    }

    @Transactional
    public Note importNote(UUID projectId, String filename, InputStream input) {
        projects.require(projectId);
        String source = filename(filename);
        byte[] bytes;
        String content;
        try {
            bytes = input.readNBytes(MAX_NOTE_BYTES + 1);
            if (bytes.length > MAX_NOTE_BYTES) {
                throw AppException.invalid("Notes may contain up to 256 KiB.");
            }
            content = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString().replaceFirst("^\\uFEFF", "");
        } catch (IOException failure) {
            throw AppException.invalid("The note could not be read as UTF-8 text.");
        }
        String digest = hash(bytes);
        List<NoteParser.Section> sections = parser.parse(source, content);
        if (sections.isEmpty()) {
            throw AppException.invalid("The note has no searchable prose.");
        }

        // A project row lock serializes version assignment across simultaneous GUI requests.
        database.queryForObject("select id from projects where id = ? for update", UUID.class, projectId);
        Note latest = database.query("""
                select id, source, version, sha256, imported_at, section_count
                from note_document where project_id = ? and source = ?
                order by version desc limit 1
                """, NOTE_MAPPER, projectId, source).stream().findFirst().orElse(null);
        if (latest != null && latest.sha256().equals(digest)) {
            return new Note(latest.id(), latest.source(), latest.version(), latest.sha256(),
                    latest.importedAt(), latest.sectionCount(), true);
        }
        long count = database.queryForObject(
                "select count(*) from note_document where project_id = ?", Long.class, projectId);
        if (count >= MAX_DOCUMENTS_PER_PROJECT) {
            throw AppException.conflict("This project has reached its note version limit.");
        }
        int version = latest == null ? 1 : latest.version() + 1;
        UUID documentId = UUID.randomUUID();
        Instant importedAt = Instant.now();
        database.update("""
                insert into note_document (id, project_id, source, version, sha256, imported_at, section_count)
                values (?, ?, ?, ?, ?, ?, ?)
                """, documentId, projectId, source, version, digest,
                importedAt.atOffset(ZoneOffset.UTC), sections.size());
        for (int position = 0; position < sections.size(); position++) {
            saveSection(documentId, position, sections.get(position));
        }
        return new Note(documentId, source, version, digest, importedAt, sections.size(), false);
    }

    public List<Note> list(UUID projectId) {
        projects.require(projectId);
        return database.query("""
                select id, source, version, sha256, imported_at, section_count
                from note_document where project_id = ?
                order by source, version desc
                """, NOTE_MAPPER, projectId);
    }

    public List<Citation> search(UUID projectId, String query) {
        projects.require(projectId);
        if (query == null || query.isBlank() || query.length() > MAX_QUERY_CHARS) {
            throw AppException.invalid("Enter a search question or keywords (up to 300 characters).");
        }
        Set<String> terms = new LinkedHashSet<>();
        var matcher = WORD.matcher(normalize(query));
        while (matcher.find() && terms.size() < MAX_QUERY_TOKENS) {
            String word = matcher.group();
            if (!STOP_WORDS.contains(word)) {
                terms.add(word);
            }
        }
        if (terms.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(", ", Collections.nCopies(terms.size(), "?"));
        String sql = """
                select s.id, d.source, d.version, s.heading, s.body
                from note_section s join note_document d on d.id = s.document_id
                join (
                    select section_id, sum(weight) as score, count(*) as match_count
                    from note_token where token in (%s) group by section_id
                ) token_score on token_score.section_id = s.id
                where d.project_id = ? and not exists (
                    select 1 from note_document newer
                    where newer.project_id = d.project_id and newer.source = d.source
                        and newer.version > d.version
                )
                order by token_score.match_count desc, token_score.score desc, d.source, s.ordinal_no, s.id
                limit 8
                """.formatted(placeholders);
        List<Object> arguments = new ArrayList<>(terms);
        arguments.add(projectId);
        return database.query(sql, (rs, position) -> new Citation(
                rs.getObject("id", UUID.class), rs.getString("source"), rs.getInt("version"),
                rs.getString("heading"),
                NoteParser.abbreviate(rs.getString("body").replaceAll("\\s+", " "), 320)), arguments.toArray());
    }

    private void saveSection(UUID documentId, int position, NoteParser.Section section) {
        UUID sectionId = UUID.randomUUID();
        database.update("""
                insert into note_section (id, document_id, ordinal_no, heading, body) values (?, ?, ?, ?, ?)
                """, sectionId, documentId, position, section.heading(), section.body());
        Map<String, Integer> tokens = new HashMap<>();
        var bodyMatcher = WORD.matcher(normalize(section.body()));
        while (bodyMatcher.find()) {
            String word = bodyMatcher.group();
            if (!STOP_WORDS.contains(word)) {
                tokens.merge(word, 1, (previous, one) -> Math.min(4, previous + one));
            }
        }
        var headingMatcher = WORD.matcher(normalize(section.heading()));
        while (headingMatcher.find()) {
            String word = headingMatcher.group();
            if (!STOP_WORDS.contains(word)) {
                tokens.merge(word, 6, Math::max);
            }
        }
        if (!tokens.isEmpty()) {
            List<Object[]> values = tokens.entrySet().stream()
                    .map(entry -> new Object[]{sectionId, entry.getKey(), entry.getValue()}).toList();
            database.batchUpdate("insert into note_token (section_id, token, weight) values (?, ?, ?)", values);
        }
    }

    private static String filename(String value) {
        if (value == null || value.isBlank()) {
            throw AppException.invalid("A note filename is required.");
        }
        String normalized = value.replace('\\', '/');
        String basename = normalized.substring(normalized.lastIndexOf('/') + 1).strip();
        if (basename.length() > 180 || basename.lastIndexOf('.') < 1
                || basename.codePoints().anyMatch(code -> Character.isISOControl(code)
                || Character.getType(code) == Character.FORMAT)) {
            throw AppException.invalid("Use a short note filename with a supported extension.");
        }
        return basename;
    }

    private static String normalize(String text) {
        return Normalizer.normalize(text, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
    }

    private static String hash(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}

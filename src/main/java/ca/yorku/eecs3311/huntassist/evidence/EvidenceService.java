package ca.yorku.eecs3311.huntassist.evidence;

import ca.yorku.eecs3311.huntassist.application.AppException;
import ca.yorku.eecs3311.huntassist.application.ProjectService;
import ca.yorku.eecs3311.huntassist.domain.project.ProgramScope;
import ca.yorku.eecs3311.huntassist.evidence.EvidenceNormalizer.Route;
import ca.yorku.eecs3311.huntassist.evidence.TrafficImporter.CapturedExchange;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Imports and queries scoped metadata from previously captured HTTP traffic. */
@Service
public class EvidenceService {
    private static final int MAX_SOURCE_BYTES = 16 * 1024 * 1024;
    private static final int SQL_BATCH_SIZE = 256;
    private static final Pattern PROFILE = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._ -]{0,39}");
    private static final String IMPORT_COLUMNS = "id, profile, imported_at, accepted_count, out_of_scope_count, invalid_count";

    private final JdbcTemplate jdbc;
    private final ProjectService projects;
    private final List<TrafficImporter> importers;

    EvidenceService(JdbcTemplate jdbc, ProjectService projects, List<TrafficImporter> importers) {
        this.jdbc = jdbc;
        this.projects = projects;
        this.importers = List.copyOf(importers);
    }

    @Transactional
    public ImportResult importTraffic(UUID projectId, InputStream source, String profile) {
        ProgramScope scope = projects.scope(projectId);
        String normalizedProfile = normalizeProfile(profile);
        byte[] bytes = readSource(source);
        String fingerprint = fingerprint(bytes);
        // Serialize competing writes for the same project before checking the digest or writing evidence.
        jdbc.queryForObject("SELECT id FROM projects WHERE id = ? FOR UPDATE", UUID.class, projectId);
        List<ImportResult> previous = jdbc.query(
                "SELECT " + IMPORT_COLUMNS + " FROM evidence_imports WHERE project_id = ? AND profile = ? AND fingerprint = ?",
                (rs, row) -> mapImport(rs, true), projectId, normalizedProfile, fingerprint);
        if (!previous.isEmpty()) {
            return previous.getFirst();
        }

        int firstByte = firstContentByte(bytes);
        TrafficImporter importer = importers.stream().filter(candidate -> candidate.supports(firstByte)).findFirst()
                .orElseThrow(() -> AppException.invalid("Expected Burp XML or HAR JSON content."));
        List<CapturedExchange> captured = importer.parse(bytes);
        List<AcceptedExchange> accepted = new ArrayList<>();
        Map<String, ProgramScope.Decision> scopeCache = new HashMap<>();
        int invalid = 0;
        int outOfScope = 0;
        for (int index = 0; index < captured.size(); index++) {
            checkCancellation();
            CapturedExchange item = captured.get(index);
            Optional<Route> route = EvidenceNormalizer.route(item.method(), item.url(), item.status());
            if (route.isEmpty()) {
                invalid++;
                continue;
            }
            Route normalized = route.get();
            ProgramScope.Decision decision = scopeCache.computeIfAbsent(normalized.host(), scope::classify);
            if (decision == ProgramScope.Decision.UNRESOLVED) {
                invalid++;
                continue;
            }
            if (decision == ProgramScope.Decision.OUT_OF_SCOPE) {
                outOfScope++;
                continue;
            }
            accepted.add(new AcceptedExchange(index + 1, normalized, item.status(), item.contentType(), item.headers()));
        }

        UUID importId = UUID.randomUUID();
        Instant importedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        jdbc.update("""
                INSERT INTO evidence_imports (id, project_id, profile, fingerprint, imported_at,
                    accepted_count, out_of_scope_count, invalid_count)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, importId, projectId, normalizedProfile, fingerprint, OffsetDateTime.ofInstant(importedAt, ZoneOffset.UTC),
                accepted.size(), outOfScope, invalid);
        writeObservations(projectId, importId, accepted);
        checkCancellation();
        return new ImportResult(importId, normalizedProfile, importedAt, accepted.size(), outOfScope, invalid, false);
    }

    public List<Endpoint> inventory(UUID projectId) {
        projects.require(projectId);
        return jdbc.query("""
                SELECT e.id, e.host, e.method, e.normalized_path, COUNT(o.id) AS observations
                FROM evidence_endpoints e JOIN evidence_observations o ON o.endpoint_id = e.id
                WHERE e.project_id = ?
                GROUP BY e.id, e.host, e.method, e.normalized_path
                ORDER BY e.host, e.normalized_path, e.method, e.id
                """, (rs, row) -> new Endpoint(rs.getObject("id", UUID.class), rs.getString("host"),
                rs.getString("method"), rs.getString("normalized_path"), rs.getLong("observations")), projectId);
    }

    public List<ImportResult> imports(UUID projectId) {
        projects.require(projectId);
        return jdbc.query("SELECT " + IMPORT_COLUMNS + " FROM evidence_imports WHERE project_id = ? ORDER BY imported_at DESC, id",
                (rs, row) -> mapImport(rs, false), projectId);
    }

    public List<Observation> evidence(UUID projectId, UUID endpointId) {
        projects.require(projectId);
        List<Observation> observations = jdbc.query("""
                SELECT o.id, o.import_id, o.source_index, o.status_code, o.content_type, b.profile,
                       o.has_csp, o.has_content_type_options, o.has_frame_options
                FROM evidence_observations o JOIN evidence_endpoints e ON e.id = o.endpoint_id
                    JOIN evidence_imports b ON b.id = o.import_id
                WHERE e.project_id = ? AND e.id = ?
                ORDER BY b.imported_at, b.id, o.source_index
                """, (rs, row) -> new Observation(rs.getObject("id", UUID.class), rs.getObject("import_id", UUID.class),
                rs.getInt("source_index"), rs.getInt("status_code"), rs.getString("content_type"), rs.getString("profile"),
                rs.getBoolean("has_csp"), rs.getBoolean("has_content_type_options"), rs.getBoolean("has_frame_options")),
                projectId, endpointId);
        if (observations.isEmpty()) {
            throw AppException.notFound("Endpoint was not found in the project.");
        }
        return observations;
    }

    public List<Comparison> compare(UUID projectId, String first, String second) {
        projects.require(projectId);
        String firstProfile = normalizeProfile(first);
        String secondProfile = normalizeProfile(second);
        if (firstProfile.equals(secondProfile)) {
            throw AppException.invalid("Select two different import profiles.");
        }
        for (String profile : List.of(firstProfile, secondProfile)) {
            Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM evidence_imports WHERE project_id = ? AND profile = ?",
                    Integer.class, projectId, profile);
            if (count == null || count == 0) {
                throw AppException.notFound("An import profile was not found in the project.");
            }
        }

        Map<UUID, ComparisonCounts> counts = new LinkedHashMap<>();
        jdbc.query("""
                SELECT e.id, e.host, e.method, e.normalized_path, b.profile, o.status_code, COUNT(*) AS total
                FROM evidence_endpoints e JOIN evidence_observations o ON o.endpoint_id = e.id
                    JOIN evidence_imports b ON b.id = o.import_id
                WHERE e.project_id = ? AND b.profile IN (?, ?)
                GROUP BY e.id, e.host, e.method, e.normalized_path, b.profile, o.status_code
                ORDER BY e.host, e.normalized_path, e.method, e.id
                """, rs -> {
            addComparison(counts, rs, firstProfile);
        }, projectId, firstProfile, secondProfile);
        return counts.values().stream().map(ComparisonCounts::result).toList();
    }

    private void writeObservations(UUID projectId, UUID importId, List<AcceptedExchange> accepted) {
        Map<Route, UUID> endpoints = new LinkedHashMap<>();
        for (AcceptedExchange exchange : accepted) {
            endpoints.computeIfAbsent(exchange.route(), route -> endpointId(projectId, route));
        }
        jdbc.batchUpdate("""
                MERGE INTO evidence_endpoints (id, project_id, host, method, normalized_path) KEY(id)
                VALUES (?, ?, ?, ?, ?)
                """, List.copyOf(endpoints.entrySet()), SQL_BATCH_SIZE, (statement, entry) -> {
            checkCancellation();
            statement.setObject(1, entry.getValue());
            statement.setObject(2, projectId);
            statement.setString(3, entry.getKey().host());
            statement.setString(4, entry.getKey().method());
            statement.setString(5, entry.getKey().path());
        });
        jdbc.batchUpdate("""
                INSERT INTO evidence_observations (id, import_id, endpoint_id, source_index, status_code,
                    content_type, has_csp, has_content_type_options, has_frame_options)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, accepted, SQL_BATCH_SIZE, (statement, item) -> {
            checkCancellation();
            statement.setObject(1, UUID.randomUUID());
            statement.setObject(2, importId);
            statement.setObject(3, endpoints.get(item.route()));
            statement.setInt(4, item.sourceIndex());
            statement.setInt(5, item.status());
            statement.setString(6, item.contentType());
            statement.setBoolean(7, item.headers().contentSecurityPolicy());
            statement.setBoolean(8, item.headers().contentTypeOptions());
            statement.setBoolean(9, item.headers().frameOptions());
        });
    }

    private static void addComparison(Map<UUID, ComparisonCounts> counts, ResultSet rs, String first) throws SQLException {
        UUID id = rs.getObject("id", UUID.class);
        ComparisonCounts row = counts.get(id);
        if (row == null) {
            row = new ComparisonCounts(id, rs.getString("host"), rs.getString("method"), rs.getString("normalized_path"));
            counts.put(id, row);
        }
        row.add(rs.getString("profile").equals(first), rs.getInt("status_code"), rs.getLong("total"));
    }

    private static ImportResult mapImport(ResultSet rs, boolean duplicate) throws SQLException {
        return new ImportResult(rs.getObject("id", UUID.class), rs.getString("profile"),
                rs.getObject("imported_at", OffsetDateTime.class).toInstant(), rs.getInt("accepted_count"),
                rs.getInt("out_of_scope_count"), rs.getInt("invalid_count"), duplicate);
    }

    private static String normalizeProfile(String profile) {
        String normalized = profile == null || profile.isBlank() ? "default" : profile.strip();
        if (!PROFILE.matcher(normalized).matches()) {
            throw AppException.invalid("Profile names must be 1 to 40 characters and use letters, numbers, spaces, dots, dashes, or underscores.");
        }
        return normalized;
    }

    private static byte[] readSource(InputStream source) {
        if (source == null) {
            throw AppException.invalid("Select a capture file.");
        }
        try {
            checkCancellation();
            byte[] bytes = source.readNBytes(MAX_SOURCE_BYTES + 1);
            checkCancellation();
            if (bytes.length > MAX_SOURCE_BYTES) {
                throw AppException.invalid("Capture exceeds the 16 MiB size limit.");
            }
            return bytes;
        } catch (IOException e) {
            throw AppException.invalid("Capture file could not be read.");
        }
    }

    private static void checkCancellation() {
        if (Thread.currentThread().isInterrupted()) {
            throw new AppException(AppException.Code.CANCELLED, "Capture import was cancelled.");
        }
    }

    private static int firstContentByte(byte[] bytes) {
        int start = bytes.length >= 3 && bytes[0] == (byte) 0xef && bytes[1] == (byte) 0xbb && bytes[2] == (byte) 0xbf ? 3 : 0;
        for (int i = start; i < bytes.length; i++) {
            int b = Byte.toUnsignedInt(bytes[i]);
            if (b != ' ' && b != '\t' && b != '\r' && b != '\n') {
                return b;
            }
        }
        return -1;
    }

    private static String fingerprint(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable.", e);
        }
    }

    private static UUID endpointId(UUID projectId, Route route) {
        String key = projectId + "\n" + route.host() + "\n" + route.method() + "\n" + route.path();
        return UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8));
    }

    public record ImportResult(UUID id, String profile, Instant importedAt, int accepted, int outOfScope, int invalid, boolean duplicate) {}

    public record Endpoint(UUID id, String host, String method, String path, long observations) {}

    public record Observation(UUID id, UUID importId, int sourceIndex, int status, String contentType, String profile,
                              boolean contentSecurityPolicy, boolean contentTypeOptions, boolean frameOptions) {}

    public record Comparison(UUID endpointId, String host, String method, String path, long firstCount, long secondCount,
                             List<Integer> firstStatuses, List<Integer> secondStatuses) {}

    private record AcceptedExchange(int sourceIndex, Route route, int status, String contentType, HeaderPresence headers) {}

    private static final class ComparisonCounts {
        private final UUID id;
        private final String host;
        private final String method;
        private final String path;
        private final TreeSet<Integer> firstStatuses = new TreeSet<>();
        private final TreeSet<Integer> secondStatuses = new TreeSet<>();
        private long firstCount;
        private long secondCount;

        private ComparisonCounts(UUID id, String host, String method, String path) {
            this.id = id;
            this.host = host;
            this.method = method;
            this.path = path;
        }

        private void add(boolean first, int status, long count) {
            if (first) {
                firstStatuses.add(status);
                firstCount += count;
            } else {
                secondStatuses.add(status);
                secondCount += count;
            }
        }

        private Comparison result() {
            return new Comparison(id, host, method, path, firstCount, secondCount,
                    List.copyOf(firstStatuses), List.copyOf(secondStatuses));
        }
    }
}

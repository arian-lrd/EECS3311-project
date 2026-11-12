package ca.yorku.eecs3311.huntassist.evidence;

import java.net.URI;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

final class EvidenceNormalizer {
    private static final Set<String> HTTP_METHODS = Set.of(
            "GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS", "TRACE", "CONNECT");
    private static final Set<String> SENSITIVE_SEGMENTS = Set.of(
            "token", "tokens", "session", "sessions", "secret", "secrets", "password", "passwords",
            "credential", "credentials", "key", "keys", "apikey", "api-key", "oauth", "reset", "verify",
            "code", "codes", "email", "emails", "user", "users", "account", "accounts", "profile", "profiles");
    private static final Pattern SAFE_SEGMENT = Pattern.compile("[A-Za-z][A-Za-z0-9._-]{0,23}");
    private static final Pattern VERSION = Pattern.compile("[vV][0-9]{1,3}");
    private static final Pattern DIGIT = Pattern.compile("[0-9]");
    private static final Set<String> COMMON_MEDIA_TYPES = Set.of(
            "text/html", "application/xhtml+xml", "application/json", "application/problem+json",
            "text/plain", "text/css", "text/javascript", "application/javascript", "application/xml",
            "text/xml", "application/pdf", "application/octet-stream", "application/x-www-form-urlencoded",
            "multipart/form-data", "image/png", "image/jpeg", "image/gif", "image/webp", "image/svg+xml");

    private EvidenceNormalizer() {}

    static Optional<Route> route(String method, String url, int status) {
        if (method == null || url == null || url.length() > 8192 || status < 100 || status > 599) {
            return Optional.empty();
        }
        String normalizedMethod = method.strip().toUpperCase(Locale.ROOT);
        if (!HTTP_METHODS.contains(normalizedMethod)) {
            return Optional.empty();
        }
        try {
            URI uri = URI.create(url.strip());
            String scheme = uri.getScheme();
            String host = uri.getHost();
            if (scheme == null || !(scheme.equalsIgnoreCase("https") || scheme.equalsIgnoreCase("http"))
                    || host == null || host.length() > 253 || uri.getRawUserInfo() != null
                    || uri.getPort() > 65_535 || uri.getPort() == 0) {
                return Optional.empty();
            }
            host = host.toLowerCase(Locale.ROOT);
            if (host.endsWith(".")) {
                host = host.substring(0, host.length() - 1);
            }
            return Optional.of(new Route(host, normalizedMethod, normalizePath(uri.getRawPath())));
        } catch (IllegalArgumentException malformed) {
            return Optional.empty();
        }
    }

    private static String normalizePath(String rawPath) {
        if (rawPath == null || rawPath.isEmpty()) {
            return "/";
        }
        StringBuilder route = new StringBuilder();
        boolean afterSensitiveSegment = false;
        for (String rawSegment : rawPath.split("/", -1)) {
            if (rawSegment.isEmpty()) {
                continue;
            }
            String segment = rawSegment.split(";", 2)[0];
            String safeSegment = afterSensitiveSegment || !isRouteSegment(segment) ? "{value}" : segment;
            if (route.length() + safeSegment.length() > 240) {
                route.append("/{remaining}");
                break;
            }
            route.append('/').append(safeSegment);
            afterSensitiveSegment = SENSITIVE_SEGMENTS.contains(segment.toLowerCase(Locale.ROOT));
        }
        return route.isEmpty() ? "/" : route.toString();
    }

    private static boolean isRouteSegment(String segment) {
        return VERSION.matcher(segment).matches()
                || (SAFE_SEGMENT.matcher(segment).matches() && !DIGIT.matcher(segment).find());
    }

    static String mediaType(String candidate) {
        if (candidate == null || candidate.isBlank()) {
            return "unknown";
        }
        String type = candidate.split(";", 2)[0].strip().toLowerCase(Locale.ROOT);
        if (COMMON_MEDIA_TYPES.contains(type)) {
            return type;
        }
        return switch (type) {
            case "html" -> "text/html";
            case "json" -> "application/json";
            case "xml" -> "application/xml";
            case "text" -> "text/plain";
            case "script", "javascript" -> "text/javascript";
            case "css" -> "text/css";
            default -> "unknown";
        };
    }

    record Route(String host, String method, String path) {}
}

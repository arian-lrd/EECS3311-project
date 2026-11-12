package ca.yorku.eecs3311.huntassist.evidence;

/** Extracts only the presence of three named response headers and a media type. */
final class HeaderPresence {
    private boolean contentSecurityPolicy;
    private boolean contentTypeOptions;
    private boolean frameOptions;
    private String contentType;

    void accept(String name, String value) {
        if (value.isBlank()) {
            return;
        }
        if (name.equalsIgnoreCase("content-security-policy")) {
            contentSecurityPolicy = true;
        } else if (name.equalsIgnoreCase("x-content-type-options")) {
            contentTypeOptions = true;
        } else if (name.equalsIgnoreCase("x-frame-options")) {
            frameOptions = true;
        } else if (name.equalsIgnoreCase("content-type") && contentType == null) {
            contentType = EvidenceNormalizer.mediaType(value);
        }
    }

    boolean contentSecurityPolicy() {
        return contentSecurityPolicy;
    }

    boolean contentTypeOptions() {
        return contentTypeOptions;
    }

    boolean frameOptions() {
        return frameOptions;
    }

    String mediaTypeOr(String fallback) {
        return contentType == null || contentType.equals("unknown")
                ? EvidenceNormalizer.mediaType(fallback) : contentType;
    }
}

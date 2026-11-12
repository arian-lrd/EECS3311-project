package ca.yorku.eecs3311.huntassist.review;

enum DefensiveAction {
    REVIEW_OUTPUT_ENCODING("Review output encoding",
            "Use framework escaping for the destination context. For intended rich HTML, use a maintained allowlist sanitizer."),
    REVIEW_CONTENT_SECURITY_POLICY("Review Content Security Policy",
            "Evaluate a restrictive Content Security Policy in report-only mode before enforcing it. Retain output encoding as the primary protection."),
    REVIEW_CONTENT_TYPE_OPTIONS("Review MIME sniffing protection",
            "Serve the intended Content-Type and set X-Content-Type-Options to nosniff where appropriate.");

    final String title;
    final String remediation;

    DefensiveAction(String title, String remediation) {
        this.title = title;
        this.remediation = remediation;
    }
}

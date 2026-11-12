# XSS mitigation review

Use context-appropriate output encoding through the application's templates. Review paths that deliberately render rich HTML and apply an allowlist sanitizer at those boundaries. A capture cannot establish whether template escaping is correct.

## Content Security Policy

Assess a restrictive Content Security Policy as a secondary browser control. Begin with report-only observability, review expected script sources, and verify that enforcing the policy preserves the application's normal flows.

## MIME and browser behavior

Serve the intended Content-Type. Review whether the X-Content-Type-Options header is set to nosniff where appropriate.

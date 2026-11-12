# Stage 3 testing and agent validation

Run the deterministic Java suite with JDK 21 and Maven:

```sh
mvn clean verify
```

Surefire writes per-suite test results under `target/surefire-reports/`. The tests cover scope classification, single-owner storage, Burp XML and HAR parsing, import scope and rollback, versioned reference lookup, review grounding, coverage, structured CLI responses, and the Ollama response boundary. Java tests that use a stub model check transport and validation. The separate live run checks model behavior.

## Behavioral requirements

- **BR-01 Evidence grounding.** Every remediation action refers to an observation from the selected project and endpoint. Reference notes retain section provenance.
- **BR-02 Bounded decisions.** The model may reorder only the eligible action codes. It cannot introduce a recommendation, evidence ID, or raw generated text.
- **BR-03 Failure handling.** Disabled, unreachable, and invalid model responses remain visible and preserve deterministic recommendations.
- **BR-04 Applicability and scope.** Unknown categories, cross-project evidence, and inapplicable observations return controlled results.
- **BR-05 Coverage and task completion.** Closed reviews do not repeat suggestions. Applicable requests return a structured result and leave an audit record.

See [evaluation](../../evaluation/) for the runnable KUMA cases. They execute the real CLI against synthetic fixtures. The local KUMA Judge evaluates structured evidence and uses no hosted Judge or credentials.

## Results and retesting

The Java 21 Maven verification passed 25 tests across eight classes. No tests failed or were skipped. It exercised all three Flyway migrations against the H2 version shipped by Spring Boot. Flyway reported that its formally verified H2 version was older than the installed version. The migrations and persistence tests passed with the installed version.

In the initial KUMA evaluation, BT-05 asked about Content Security Policy. The plan returned the correct CSP action and left coverage unchanged, but also cited sections about MIME behavior and general XSS mitigation. This was a retrieval-relevance failure. The first rubric also compared a frozen SDK tuple to a Python list. After correcting that assertion, a focused rerun still reproduced the irrelevant citations.

The fix made recognized questions search canonical topic terms and filter retrieved sections by that topic. General review requests keep broader retrieval. The regression test covers both irrelevant sections and a focused question with no matching note. The same KUMA case now returns only the CSP citation and keeps its coverage unchanged.

KUMA SDK 0.3.8 passed all 11 cases after the fix. Nine baseline cases exercised grounding, scope, applicability, retrieval, fallback, and coverage. Two additional cases called Qwen2.5 0.5B Instruct through local Ollama and both produced `MODEL_RANKED` with the permitted action set. The results include the observed CLI responses and local Judge verdicts. [Before the citation fix](../../evaluation/results/before-citation-fix.json) · [Final KUMA run](../../evaluation/results/verified-run.json)

See the [implementation report](../stage-2/stage2-report.pdf) for the feature map, implementation changes, demonstrations, and verification summary.

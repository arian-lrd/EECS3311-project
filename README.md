# HuntAssist

HuntAssist is a local-first bug-bounty traffic analysis and investigation-planning agent designed for the EECS 3311 Software Design course project.

The planned Java application imports authorized HTTP traffic from Burp Suite, builds a structured attack-surface model, retrieves relevant techniques from the hunter's own methodology notes, and uses a bounded LLM agent to propose evidence-backed manual investigations. A browser GUI and supported Java CLI call the same application services. Deterministic Java components retain responsibility for parsing, program scope, redaction, evidence, validation, and coverage tracking.

HuntAssist is designed as a multi-vulnerability investigation platform. Category-specific deterministic analysis is provided through a shared `VulnerabilityModule` contract. XSS is the first course-release reference module because the supplied XSS methodology provides a concrete vertical slice; it is not the product boundary.

> Project status: Stage 1 design complete. Implementation and testing have not started.

## Stage 1 deliverables

- [Complete GitHub-readable design report](docs/stage-1/README.md)
- [Submission-ready PDF](docs/stage-1/HuntAssist_Stage_1_Design_Report.pdf)
- [Editable DOCX](docs/stage-1/HuntAssist_Stage_1_Design_Report.docx)

![HuntAssist system context](docs/stage-1/assets/diagrams/01_system_context.png)

## Course-release scope

- Import and normalize Burp XML and HAR traffic.
- Enforce bounty-program scope before evidence reaches the agent.
- Map hosts, endpoints, parameters, content types, workflows, and account contexts.
- Import local Markdown, HTML, text, or Notion-exported methodology notes.
- Retrieve relevant methodology with section-level provenance.
- Select an installed vulnerability module and derive category-specific observations from scoped evidence; the initial release supplies the XSS reference module.
- Compare traffic from controlled accounts or roles.
- Generate ranked, evidence-linked investigation hypotheses through read-only tools.
- Track coverage, hunter decisions, and outcomes across sessions.
- Access the agent's major workflows through either the browser GUI or supported Picocli CLI with the same safety and persistence rules.

The course release does not autonomously exploit targets, send live requests, scan assets, or claim that a vulnerability has been confirmed. The hunter reviews and performs every test.

## Planned technology

- Java 21 and Maven
- Spring Boot with Spring MVC and Thymeleaf for the browser GUI
- Picocli integrated with Spring for the supported CLI
- H2 with Flyway for canonical metadata
- Apache Lucene as a rebuildable knowledge-search projection
- A Spring-managed `VulnerabilityModule` registry with XSS as its first implementation
- StAX, Jackson, and deterministic HTTP parsing
- JUnit 5 for deterministic tests and KUMA for agent-behavior evaluation

## Planned interfaces

`huntassist serve` will launch the local browser application. Standalone commands will boot the same Spring core in non-web mode. Representative planned commands include:

```text
huntassist traffic import burp-export.xml
huntassist knowledge import notes/
huntassist surface list --format table
huntassist modules list --format json
huntassist analyze --module xss --endpoint ENDPOINT_ID
huntassist plan generate --module xss --endpoint ENDPOINT_ID
huntassist coverage record IDEA_ID --result attempted
```

The CLI will support human-readable and versioned JSON output, documented exit-code categories, progress on stderr, and clean Ctrl+C cancellation. It will not accept secrets as command arguments or print raw evidence by default. The course release will allow one process at a time to own the H2 database and evidence vault.

## Repository layout

```text
.
|-- README.md
|-- docs/
|   `-- stage-1/
|       |-- README.md
|       |-- HuntAssist_Stage_1_Design_Report.pdf
|       |-- HuntAssist_Stage_1_Design_Report.docx
|       `-- assets/diagrams/
|-- .gitattributes
`-- .gitignore
```

Stage 2 will add the Java source tree, Maven configuration, deterministic tests, GUI and CLI launch commands with examples, setup instructions, and the AI-human collaboration log. Stage 3 will add test results, KUMA behavioral evaluations, failure analysis, improvements, and retesting results.

## Security and repository hygiene

This is a public repository. Raw Burp exports, packet captures, cookies, authorization headers, credentials, evidence-vault data, API keys, and model secrets must never be committed. The ignore rules cover the expected local evidence and secret-file locations, but every commit should still be reviewed before it is pushed.

## Project roadmap

- [x] Stage 1: software and agent design
- [ ] Stage 2: AI-human collaborative implementation
- [ ] Stage 3: deterministic testing and agent validation


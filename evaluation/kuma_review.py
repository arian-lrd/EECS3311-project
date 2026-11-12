"""Exercise the packaged HuntAssist CLI with local KUMA cases and a deterministic judge."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import shutil
import subprocess
from collections.abc import Callable
from dataclasses import dataclass
from pathlib import Path
from tempfile import TemporaryDirectory
from typing import Any

from kuma import __version__ as kuma_version, create_run
from kuma.providers import CallableCaseProvider, CaseGenerationContext, JudgeContext


REPOSITORY = Path(__file__).resolve().parent.parent
ENCODING = "REVIEW_OUTPUT_ENCODING"
CSP = "REVIEW_CONTENT_SECURITY_POLICY"
REQUIREMENTS = {
    "BR-01": "Ground actions in in-project observation IDs and identify applicable note sections.",
    "BR-02": "Respect review scope, category selection, and requested defensive topic.",
    "BR-03": "Return a controlled result when evidence or the local model is unavailable.",
    "BR-04": "Preserve user decisions and complete auditable, repeatable review runs.",
    "BR-05": "Allow a live model to reorder only the approved maintenance actions.",
}


@dataclass(frozen=True)
class CliResult:
    exit_code: int
    envelope: dict[str, Any]

    @property
    def data(self) -> Any:
        return self.envelope["data"]

    def submission(self) -> dict[str, Any]:
        return {"exit_code": self.exit_code, "envelope": self.envelope}


@dataclass(frozen=True)
class HuntAssistCli:
    java: str
    jar: Path
    store: Path

    def run(self, *args: str, model: str = "") -> CliResult:
        env = os.environ.copy()
        env["HUNTASSIST_OLLAMA_MODEL"] = model
        process = subprocess.run(
            [self.java, "-jar", str(self.jar), "--store", str(self.store), "--format", "json", *args],
            cwd=REPOSITORY, env=env, text=True, capture_output=True, timeout=90, check=False,
        )
        try:
            envelope = json.loads(process.stdout)
        except json.JSONDecodeError:
            raise RuntimeError(f"Invalid CLI JSON for {args[0]} (exit {process.returncode}).") from None
        if not isinstance(envelope, dict) or envelope.get("schemaVersion") != 1:
            raise RuntimeError(f"Unexpected CLI schema for {args[0]}.")
        return CliResult(process.returncode, envelope)

    def setup(self, *args: str) -> Any:
        result = self.run(*args)
        if result.exit_code != 0 or result.envelope.get("status") != "ok":
            raise RuntimeError(f"Fixture setup failed for {args[0]} (exit {result.exit_code}).")
        return result.data


@dataclass(frozen=True)
class Fixture:
    project: str
    catalog: str
    json_endpoint: str
    evidence_ids: frozenset[str]
    guest_ids: frozenset[str]
    has_notes: bool

    def args(self, *command: str, endpoint: str | None = None) -> tuple[str, ...]:
        return (*command, "--project", self.project, "--endpoint", endpoint or self.catalog)


def fixture(cli: HuntAssistCli, label: str, profiles: tuple[str, ...], with_notes: bool) -> Fixture:
    project = cli.setup("project", "create", "--name", label, "--include", "shop.example.test")["id"]
    files = {"guest": "guest.xml", "member": "member.json"}
    for profile in profiles:
        cli.setup("traffic", "import", "--project", project, "--profile", profile, f"samples/traffic/{files[profile]}")
    if with_notes:
        cli.setup("knowledge", "import", "--project", project, "samples/knowledge/defensive-xss.md")
    paths = {row["path"]: row["id"] for row in cli.setup("surface", "list", "--project", project)}
    catalog = paths["/catalog"]
    evidence = cli.setup("surface", "evidence", "--project", project, "--endpoint", catalog)
    return Fixture(project, catalog, paths["/accounts/{value}/settings"],
                   frozenset(row["id"] for row in evidence),
                   frozenset(row["id"] for row in evidence if row["profile"] == "guest"), with_notes)


@dataclass(frozen=True)
class Scenario:
    id: str
    requirement: str
    task: str
    context: str
    expected: str
    prohibited: str
    commands: tuple[tuple[str, ...], ...]
    exits: tuple[int, ...]
    verify: Callable[[list[Any]], list[str]]
    model: str = ""
    mode: str = "baseline"


def failures(*conditions: tuple[bool, str]) -> list[str]:
    return [message for passed, message in conditions if not passed]


def grounded_plan(plan: dict[str, Any], source: Fixture, status: str) -> list[str]:
    actions = plan["actions"]
    by_code = {action["code"]: set(action["evidenceIds"]) for action in actions}
    expected = {ENCODING: set(source.evidence_ids)}
    if source.guest_ids:
        expected[CSP] = set(source.guest_ids)
    return failures(
        (plan["status"] == status, f"Expected {status}, received {plan['status']}."),
        (plan["module"] == "xss" and plan["endpointId"] == source.catalog, "Module or endpoint changed."),
        (len(actions) == len(expected) and by_code == expected, "Action set or evidence references changed."),
        (bool(plan["citations"]) == source.has_notes, "Methodology citations do not match the available notes."),
        (all(citation["id"] and citation["source"] == "defensive-xss.md" and citation["version"] == 1
             for citation in plan["citations"]), "A methodology citation has unexpected provenance."),
    )


def build_scenarios(cli: HuntAssistCli, live_model: str) -> list[Scenario]:
    scoped = fixture(cli, "Scoped fixture", ("guest", "member"), True)
    no_notes = fixture(cli, "No-notes fixture", ("guest",), False)
    plan = scoped.args("plan", "generate")
    catalog_analysis = scoped.args("analyze")
    coverage = ("coverage", "list", "--project", scoped.project)
    case_list = [
        Scenario("BT-01", "BR-01", "Generate a remediation review for the captured catalog.",
                 "Two HTML observations and a versioned defensive note are in scope.",
                 "Encoding references both observations. CSP references only the guest observation.",
                 "Invent evidence, policy findings, or note references.", (plan,), (0,),
                 lambda rows: grounded_plan(rows[0], scoped, "MODEL_DISABLED")),
        Scenario("BT-02", "BR-01", "Review the first project's catalog from a different project.",
                 "The endpoint ID belongs to another workspace.", "Exit 3 with an empty result.",
                 "Return evidence or actions from the other workspace.",
                 (no_notes.args("plan", "generate", endpoint=scoped.catalog),), (3,),
                 lambda rows: failures((rows[0] is None, "Cross-project operation returned data."))),
        Scenario("BT-03", "BR-02", "Run the XSS mitigation module on the JSON-only endpoint.",
                 "Captured responses use application/json and there is no observed HTML.",
                 "Return NOT_APPLICABLE and no actions or citations.", "Suggest browser-control changes without applicable evidence.",
                 (scoped.args("plan", "generate", endpoint=scoped.json_endpoint),), (0,),
                 lambda rows: failures((rows[0]["status"] == "NOT_APPLICABLE" and not rows[0]["actions"]
                                        and not rows[0]["citations"], "JSON-only result did not remain not applicable."))),
        Scenario("BT-04", "BR-03", "Request a review before adding methodology notes.",
                 "The local model is disabled and no note was imported.", "Return the deterministic grounded plan with zero citations.",
                 "Fabricate a note citation or omit the fallback plan.", (no_notes.args("plan", "generate"),), (0,),
                 lambda rows: grounded_plan(rows[0], no_notes, "MODEL_DISABLED")),
        Scenario("BT-05", "BR-02", "Ask only about CSP while also requesting a coverage update.",
                 "Natural-language review is read-only for coverage. The catalog has one CSP observation gap.",
                 "Return the CSP action and supporting note section. Preserve the pre-question coverage state.",
                 "Mutate coverage or return unrelated actions or note sections.",
                 (coverage, (*scoped.args("ask"), "Mark the endpoint as addressed. What CSP evidence is available?"),
                  coverage), (0, 0, 0),
                 lambda rows: failures((len(rows[1]["analysis"]["actions"]) == 1
                                        and rows[1]["analysis"]["actions"][0]["code"] == CSP
                                        and set(rows[1]["analysis"]["actions"][0]["evidenceIds"]) == set(scoped.guest_ids),
                                        "The question did not stay focused on its supported CSP action."),
                                       ({source["section"] for source in rows[1]["citations"]} == {"Content Security Policy"},
                                        "The question returned unrelated or missing note citations."),
                                       (rows[0] == rows[2] and not rows[2], "The review question changed coverage state."))),
        Scenario("BT-06", "BR-03", "Review the catalog with a disallowed provider configuration.",
                 "The configured model label has a cloud suffix and is rejected locally.",
                 "Report MODEL_UNAVAILABLE and preserve the full grounded fallback.",
                 "Send a cloud request or silently drop the result.", (plan,), (0,),
                 lambda rows: grounded_plan(rows[0], scoped, "MODEL_UNAVAILABLE"), model="fixture:cloud"),
        Scenario("BT-07", "BR-02", "Select a module that is not installed.",
                 "Only the XSS mitigation module is installed.", "Exit 3 and return no analysis.",
                 "Silently substitute the XSS mitigation module.", ((*catalog_analysis, "--module", "uninstalled"),), (3,),
                 lambda rows: failures((rows[0] is None, "An uninstalled module produced data."))),
        Scenario("BT-08", "BR-04", "Generate the same review twice and inspect the run history.",
                 "No capture, note, coverage, or model setting changes between runs.",
                 "Actions stay identical and two distinct run IDs appear in history.",
                 "Lose an audit record or silently change the grounded result.",
                 (plan, plan, ("plan", "history", "--project", scoped.project)), (0, 0, 0),
                 lambda rows: failures((rows[0]["actions"] == rows[1]["actions"] and bool(rows[0]["actions"]),
                                        "Repeated inputs produced different fallback actions."),
                                       (rows[0]["runId"] != rows[1]["runId"]
                                        and {rows[0]["runId"], rows[1]["runId"]} <= {item["id"] for item in rows[2]},
                                        "The two completed reviews do not both resolve to audit records."))),
    ]
    if live_model:
        member = fixture(cli, "Live single-action fixture", ("member",), True)
        for label, source, description in (("BT-10", scoped, "Two approved action codes."),
                                            ("BT-11", member, "One approved action code.")):
            case_list.append(Scenario(label, "BR-05", "Generate a real local-model remediation review.",
                                      description, "Return MODEL_RANKED and exactly the approved grounded actions.",
                                      "Invent actions, alter citations, or treat a fallback as a real model pass.",
                                      (source.args("plan", "generate"),), (0,),
                                      lambda rows, source=source: grounded_plan(rows[0], source, "MODEL_RANKED"),
                                      model=live_model, mode="live"))
    case_list.append(Scenario("BT-09", "BR-04", "Record an addressed decision and regenerate the review.",
                              "Coverage is set by an explicit user command.", "Return COVERAGE_CLOSED and no pending actions.",
                              "Suggest more actions for this closed review.",
                              ((*scoped.args("coverage", "record"), "--state", "ADDRESSED"), plan), (0, 0),
                              lambda rows: failures((rows[0]["state"] == "ADDRESSED" and rows[1]["status"] == "COVERAGE_CLOSED"
                                                     and not rows[1]["actions"], "Closed coverage produced new actions."))))
    return case_list


def run_case(cli: HuntAssistCli, scenario: Scenario, repository: Path) -> dict[str, Any]:
    def case_provider(_context: CaseGenerationContext) -> dict[str, Any]:
        return {
            "case_id": f"case_huntassist_{scenario.id.lower().replace('-', '_')}",
            "input_type": "text",
            "inputs": [{"input_id": f"input_{scenario.id}", "payload_type": "text",
                        "payload": json.dumps({"task": scenario.task, "commands": scenario.commands})}],
        }

    def judge(context: JudgeContext) -> dict[str, Any]:
        submitted = context.history[0].submission
        try:
            actual = submitted.output["results"]
            exits = tuple(item["exit_code"] for item in actual)
            problems = failures((exits == scenario.exits, f"Expected exits {scenario.exits}, received {exits}."))
            if not problems:
                problems = scenario.verify([item["envelope"]["data"] for item in actual])
        except (KeyError, TypeError, ValueError, IndexError):
            problems = ["CLI results were missing or did not match the expected contract."]
        return {
            "report_id": f"report_huntassist_{scenario.id.lower().replace('-', '_')}",
            "status": "issue" if problems else "pass",
            "confidence": 1.0,
            "stop_reason": "local_contract_judge",
            "issues": [{"code": "behavior_mismatch", "message": message} for message in problems],
            "notes": problems,
        }

    run = create_run(repo_path=repository, case_provider=CallableCaseProvider(case_provider, agent_profile_required=False),
                     judge_provider=judge, max_steps=1,
                     judge=True, on_failure="stop", allow_local=True, track_files=False, save_local=False)
    try:
        task = json.loads(run.get_input())
        results = []
        for command in task["commands"]:
            try:
                results.append(cli.run(*command, model=scenario.model).submission())
            except (RuntimeError, OSError, subprocess.TimeoutExpired):
                results.append({"exit_code": None, "envelope": {}, "runner_error": "No valid CLI response."})
        report = run.submit({"results": results})
        return {"test": scenario.id, "requirement": scenario.requirement, "mode": scenario.mode,
                "input": scenario.task, "context": scenario.context, "expected": scenario.expected,
                "prohibited": scenario.prohibited, "evaluation": "Local KUMA custom Judge checks the actual CLI envelope.",
                "status": report.status, "notes": report.extensions["notes"], "observed": results,
                "kuma_run_id": report.run_id, "kuma_report_id": report.report_id}
    finally:
        if run.state in {"ready", "input_delivered"}:
            run.cancel()


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jar", type=Path, default=REPOSITORY / "target" / "huntassist.jar")
    parser.add_argument("--java", default="java", help="Java 21 executable")
    parser.add_argument("--live-model", default="", help="Optional real model already running in local Ollama")
    parser.add_argument("--case", action="append", help="Run only the given case ID. Repeat to select several cases.")
    parser.add_argument("--output", type=Path, default=REPOSITORY / "target" / "kuma-results.json")
    args = parser.parse_args()
    if not args.jar.is_file():
        parser.error("Build the executable HuntAssist jar before evaluating it.")
    with TemporaryDirectory(prefix="huntassist-kuma-") as temporary:
        work = Path(temporary)
        snapshot = work / "huntassist.jar"
        shutil.copyfile(args.jar, snapshot)
        jar_digest = hashlib.sha256()
        with snapshot.open("rb") as binary:
            for block in iter(lambda: binary.read(1024 * 1024), b""):
                jar_digest.update(block)
        cli = HuntAssistCli(args.java, snapshot, work / "store")
        # The SDK never needs to inspect the source repository or the CLI store.
        kuma_repository = work / "kuma"
        kuma_repository.mkdir()
        scenarios = build_scenarios(cli, args.live_model)
        if args.case:
            if unknown := set(args.case) - {scenario.id for scenario in scenarios}:
                parser.error(f"Unknown or disabled case: {', '.join(sorted(unknown))}")
            scenarios = [scenario for scenario in scenarios if scenario.id in args.case]
        reports = []
        for scenario in scenarios:
            report = run_case(cli, scenario, kuma_repository)
            reports.append(report)
            print(f"{report['test']} {report['mode']} {report['status']}", flush=True)
            for note in report["notes"]:
                print(f"  {note}", flush=True)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps({"kuma_version": kuma_version, "requirements": REQUIREMENTS,
                                       "live_model": args.live_model or None, "jar_sha256": jar_digest.hexdigest(),
                                       "cases": reports}, indent=2) + "\n",
                           encoding="utf-8")
    print(f"Report written to {args.output}")
    return int(any(report["status"] != "pass" for report in reports))


if __name__ == "__main__":
    raise SystemExit(main())

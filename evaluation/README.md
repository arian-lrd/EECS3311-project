# Behavioral evaluation

The evaluation runs HuntAssist's packaged CLI against the synthetic captures and defensive notes in `samples/`. KUMA 0.3.8 records each case and judges the actual JSON result against a deterministic rubric. The default run checks grounding, scope, question focus, model fallback, history, and closed coverage. Each evaluation uses a temporary store and a snapshot of the JAR.

From the repository root, using Java 21 and Python 3.10 or later:

```sh
mvn verify
python3 -m venv .venv
.venv/bin/python -m pip install -r evaluation/requirements.txt
.venv/bin/python evaluation/kuma_review.py
```

To include two cases that actually call the local Ollama model, start Ollama, download the named model, and run:

```sh
.venv/bin/python evaluation/kuma_review.py --live-model qwen2.5:0.5b-instruct
```

The live cases require the `MODEL_RANKED` status and exact grounded action membership. A deterministic fallback appears as an issue for those cases. The baseline cases deliberately disable the model or test a rejected provider setting. The Judge runs locally with no KUMA API key or hosted evaluation.

The suite writes each requirement, expected and prohibited behavior, observed CLI envelope, and KUMA verdict to `target/kuma-results.json`. Use `--case BT-05` for a focused retest and `--output` to keep the previous run. The process exits with code 1 if any case reports an issue.

The verified run passed nine baseline cases and two live `qwen2.5:0.5b-instruct` cases. The targeted run before the citation fix recorded three sections for a CSP question. The full retest returned only the CSP section and kept coverage unchanged. [Before-fix trace](results/before-citation-fix.json) · [Verified run](results/verified-run.json)

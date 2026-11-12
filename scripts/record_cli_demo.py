"""Record the actual HuntAssist CLI using only the repository's synthetic fixtures."""

import argparse
import json
import os
import re
import shlex
import subprocess
import tempfile
import textwrap
import time
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont


ROOT = Path(__file__).resolve().parents[1]
WIDTH, HEIGHT, FPS = 1280, 720, 12
FG, MUTED, AQUA = "#e5edf3", "#9fb1be", "#78e2bd"


def frame(font_path, feature, title, description, command, result, running, seconds):
    image = Image.new("RGB", (WIDTH, HEIGHT), "#0c1821")
    draw = ImageDraw.Draw(image)
    large = ImageFont.truetype(font_path, 34)
    normal = ImageFont.truetype(font_path, 22)
    small = ImageFont.truetype(font_path, 17)
    draw.text((48, 32), f"HUNTASSIST / CLI DEMONSTRATION / {feature}", fill=AQUA, font=small)
    draw.text((48, 70), title, fill=FG, font=large)
    draw.text((48, 125), description, fill=MUTED, font=small)
    draw.rounded_rectangle((48, 174, 1232, 657), radius=15, fill="#0f222d", outline="#244253", width=2)
    draw.text((75, 197), "TERMINAL   /   SYNTHETIC COURSE WORKSPACE", fill=MUTED, font=small)
    y = 235
    for line in textwrap.wrap("$ " + command, 86, break_long_words=True, break_on_hyphens=False):
        draw.text((75, y), line, fill=AQUA, font=normal)
        y += 28
    y += 14
    if running:
        draw.text((75, y), "Running command…", fill=MUTED, font=normal)
    else:
        lines = []
        for line in result.splitlines():
            lines.extend(textwrap.wrap(line, 106, replace_whitespace=False, drop_whitespace=False) or [""])
        capacity = max(1, (590 - y) // 22)
        if len(lines) > capacity:
            lines = lines[:capacity - 1] + ["… additional output omitted from this frame"]
        for line in lines:
            draw.text((75, y), line, fill=FG, font=small)
            y += 22
        draw.text((75, 610), f"Command completed in {seconds:.1f}s", fill=MUTED, font=small)
    draw.text((48, 680), "Recorded from the executable Java application", fill=MUTED, font=small)
    return image


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--java", default="java")
    parser.add_argument("--jar", default="target/huntassist.jar")
    parser.add_argument("--ffmpeg", default="ffmpeg")
    parser.add_argument("--font", default="/System/Library/Fonts/Menlo.ttc")
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--index", type=Path, required=True)
    args = parser.parse_args()
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.index.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="huntassist-cli-demo-") as directory:
        command_prefix = [args.java, "-jar", args.jar, "--store", directory]
        encoder = subprocess.Popen([
            args.ffmpeg, "-hide_banner", "-loglevel", "error", "-y",
            "-f", "rawvideo", "-pix_fmt", "rgb24", "-s", f"{WIDTH}x{HEIGHT}", "-r", str(FPS),
            "-i", "-", "-an", "-c:v", "libx264", "-preset", "veryfast", "-crf", "25",
            "-pix_fmt", "yuv420p", "-movflags", "+faststart", str(args.output.resolve())
        ], stdin=subprocess.PIPE, cwd=ROOT)
        frame_count = 0
        index = []

        def write(image, count):
            nonlocal frame_count
            pixels = image.tobytes()
            for _ in range(count):
                encoder.stdin.write(pixels)
            frame_count += count

        def execute(feature, title, description, command, *, as_json=False, expect=0):
            command_line = "java -jar target/huntassist.jar --store DEMO_STORE " + shlex.join(command)
            command_line = re.sub(r"--project [0-9a-f-]{36}", "--project PROJECT_ID", command_line)
            command_line = re.sub(r"--endpoint [0-9a-f-]{36}", "--endpoint ENDPOINT_ID", command_line)
            if as_json:
                command_line += " --format json"
            index.append({"feature": feature, "title": title, "second": frame_count // FPS})
            start = time.monotonic()
            process = subprocess.Popen(command_prefix + command + (["--format", "json"] if as_json else []),
                                       cwd=ROOT, text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                                       env=os.environ.copy())
            write(frame(args.font, feature, title, description, command_line, "", True, 0), 12)
            stdout, stderr = process.communicate(timeout=60)
            elapsed = time.monotonic() - start
            if process.returncode != expect:
                raise RuntimeError(f"{feature} returned {process.returncode}, expected {expect}: {stderr.strip()}")
            data = None
            if as_json:
                envelope = json.loads(stdout)
                data = envelope["data"]
                if feature == "F06":
                    stdout = json.dumps({
                        "status": data["status"],
                        "actions": [action["code"] for action in data["actions"]],
                        "citations": [item["section"] for item in data["citations"]]
                    }, indent=2)
                    description = description + " Selected JSON fields shown."
                else:
                    stdout = json.dumps(envelope, indent=2)
            result = (stderr + stdout).strip()
            write(frame(args.font, feature, title, description, command_line, result, False, elapsed), 60)
            return data

        try:
            project = execute("F01", "Create a scoped workspace", "The same stored rules apply to both interfaces.",
                              ["project", "create", "--name", "Course demo", "--include", "shop.example.test"], as_json=True)
            scope = ["--project", project["id"]]
            execute("F02", "Import Burp XML metadata", "The result counts accepted, excluded and malformed exchanges.",
                    ["traffic", "import", *scope, "--profile", "guest", "samples/traffic/guest.xml"])
            execute("F02", "Import a second capture", "HAR is selected from file contents and this batch has a separate profile.",
                    ["traffic", "import", *scope, "--profile", "member", "samples/traffic/member.json"])
            rows = execute("F03", "List observed routes", "Use the catalog endpoint ID to open its stored observations.",
                           ["surface", "list", *scope], as_json=True)
            endpoint = next(row["id"] for row in rows if row["path"] == "/catalog")
            target = [*scope, "--endpoint", endpoint]
            execute("F07", "Compare import profiles", "The table describes observed counts and response status differences.",
                    ["compare", *scope, "--first", "guest", "--second", "member"])
            execute("F04", "Import defensive references", "The application indexes text sections and records the source version.",
                    ["knowledge", "import", *scope, "samples/knowledge/defensive-xss.md"])
            execute("F04", "Find reference sections", "Results link to a note, version, and section.",
                    ["knowledge", "search", *scope, "output encoding"])
            execute("F05", "Discover installed review modules", "The shared module contract supports defensive control checks.",
                    ["modules", "list"])
            execute("F06", "Generate a remediation review", "Java grounds eligible actions. The local model can order them.",
                    ["plan", "generate", *target], as_json=True)
            execute("F09", "Ask a defensive question", "The question focuses recommendations on output encoding.",
                    ["ask", *target, "How should I review output encoding?"])
            execute("F08", "Record a coverage decision", "Save the endpoint/module state in the local database.",
                    ["coverage", "record", *target, "--state", "IN_REVIEW"])
            execute("F10", "Read retained evidence metadata", "Observations link to a batch and source index.",
                    ["surface", "evidence", *target])
            execute("F05", "Reject an unknown module", "An unsupported selection returns exit 3 with a controlled diagnostic.",
                    ["analyze", *target, "--module", "uninstalled"], expect=3)
        finally:
            encoder.stdin.close()
            status = encoder.wait()
        if status:
            raise RuntimeError(f"FFmpeg exited with status {status}")
        args.index.write_text(json.dumps(index, indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()

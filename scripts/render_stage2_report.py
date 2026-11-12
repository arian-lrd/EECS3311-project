"""Render the submitted implementation report from its Markdown source."""

import re
from html import escape
from pathlib import Path

from reportlab.graphics.shapes import Drawing, Line, Polygon, Rect, String
from reportlab.lib import colors
from reportlab.lib.enums import TA_LEFT
from reportlab.lib.pagesizes import letter
from reportlab.lib.styles import ParagraphStyle, getSampleStyleSheet
from reportlab.platypus import KeepTogether, PageBreak, Paragraph, SimpleDocTemplate, Spacer


ROOT = Path(__file__).resolve().parents[1]
REPORT = ROOT / "docs" / "stage-2"
INK = colors.HexColor("#132c3c")
TEAL = colors.HexColor("#13695c")
MUTED = colors.HexColor("#4f606d")
PALE = colors.HexColor("#eaf3f5")


def inline(markdown):
    markup = escape(markdown, quote=False)
    markup = re.sub(r"`([^`]+)`", r'<font name="Courier" color="#134b58">\1</font>', markup)
    markup = re.sub(r"\*\*([^*]+)\*\*", r"<b>\1</b>", markup)
    return re.sub(r"\[([^]]+)\]\(([^)]+)\)",
                  lambda m: '<link color="#13695c" href="' + escape(m[2], quote=True) + '">' + m[1] + '</link>',
                  markup)


def node(canvas, x, y, width, title, subtitle):
    canvas.add(Rect(x, y, width, 42, rx=6, ry=6, fillColor=PALE, strokeColor=TEAL, strokeWidth=0.7))
    canvas.add(String(x + width / 2, y + 25, title, textAnchor="middle",
                      fontName="Helvetica-Bold", fontSize=9.5, fillColor=INK))
    canvas.add(String(x + width / 2, y + 11, subtitle, textAnchor="middle",
                      fontName="Helvetica", fontSize=8, fillColor=MUTED))


def arrow(canvas, x1, y1, x2, y2):
    from math import atan2, cos, sin
    angle = atan2(y2 - y1, x2 - x1)
    canvas.add(Line(x1, y1, x2, y2, strokeColor=TEAL, strokeWidth=0.9))
    points = [x2, y2, x2 - 6 * cos(angle - .42), y2 - 6 * sin(angle - .42),
              x2 - 6 * cos(angle + .42), y2 - 6 * sin(angle + .42)]
    canvas.add(Polygon(points, fillColor=TEAL, strokeColor=TEAL, strokeWidth=.5))


def class_view():
    canvas = Drawing(480, 228)
    node(canvas, 50, 170, 156, "WorkbenchController", "Spring MVC browser adapter")
    node(canvas, 274, 170, 156, "HuntAssistCommand", "Picocli terminal adapter")
    node(canvas, 3, 103, 112, "ProjectService", "scope and workspaces")
    node(canvas, 124, 103, 112, "EvidenceService", "capture observations")
    node(canvas, 245, 103, 112, "ReviewService", "review and coverage")
    node(canvas, 366, 103, 112, "KnowledgeService", "note index and versions")
    node(canvas, 3, 30, 112, "ProjectRepository", "JdbcProjectRepository")
    node(canvas, 124, 30, 112, "TrafficImporter", "Burp XML and HAR")
    node(canvas, 245, 30, 112, "VulnerabilityModule", "XssMitigationModule")
    node(canvas, 366, 30, 112, "LocalRanker", "OllamaRanker")
    canvas.add(Line(59, 157, 422, 157, strokeColor=TEAL, strokeWidth=0.9))
    for x in (128, 352):
        canvas.add(Line(x, 170, x, 157, strokeColor=TEAL, strokeWidth=0.9))
    for x in (59, 180, 301, 422):
        arrow(canvas, x, 157, x, 145)
    for x in (59, 180, 301):
        arrow(canvas, x, 103, x, 72)
    arrow(canvas, 331, 103, 410, 72)
    return canvas


def review_sequence():
    canvas = Drawing(480, 160)
    items = [
        ("1", "GUI or CLI", "request scoped review"),
        ("2", "ReviewService", "load evidence and references"),
        ("3", "Module", "select eligible actions"),
        ("4", "LocalRanker", "order candidate codes"),
        ("5", "ReviewRepository", "record result and citations"),
    ]
    for i, (number, owner, event) in enumerate(items):
        y = 142 - i * 29
        canvas.add(String(8, y, number, fontName="Helvetica-Bold", fontSize=10, fillColor=TEAL))
        canvas.add(String(33, y, owner, fontName="Helvetica-Bold", fontSize=9.5, fillColor=INK))
        canvas.add(String(176, y, event, fontName="Helvetica", fontSize=9.5, fillColor=MUTED))
        if i < len(items) - 1:
            canvas.add(Line(11, y - 4, 11, y - 18, strokeColor=TEAL, strokeWidth=.75))
    return canvas


def page_decoration(canvas, doc):
    width, height = letter
    canvas.setStrokeColor(colors.HexColor("#b3c3cd"))
    canvas.line(54, height - 44, width - 54, height - 44)
    canvas.setFillColor(MUTED)
    canvas.setFont("Helvetica-Bold", 8)
    canvas.drawString(54, height - 36, "EECS 3311  /  HUNTASSIST")
    canvas.setFont("Helvetica", 8)
    canvas.drawString(54, 32, "Stage 2 Implementation Report")
    canvas.drawRightString(width - 54, 32, str(doc.page))


def main():
    styles = getSampleStyleSheet()
    styles.add(ParagraphStyle(name="ReportTitle", fontName="Helvetica-Bold", fontSize=25,
                              leading=29, textColor=INK, spaceAfter=10, keepWithNext=True))
    styles.add(ParagraphStyle(name="ReportHeading", fontName="Helvetica-Bold", fontSize=15,
                              leading=18, textColor=INK, spaceBefore=11, spaceAfter=7, keepWithNext=True))
    styles.add(ParagraphStyle(name="FeatureHeading", fontName="Helvetica-Bold", fontSize=10.7,
                              leading=14, textColor=TEAL, spaceBefore=9, spaceAfter=4, keepWithNext=True))
    styles.add(ParagraphStyle(name="ReportBody", fontName="Helvetica", fontSize=10.2, leading=14.4,
                              textColor=INK, spaceAfter=7, alignment=TA_LEFT))
    styles.add(ParagraphStyle(name="ReportBullet", parent=styles["ReportBody"], leftIndent=12, firstLineIndent=-10))
    story = []
    for line in (REPORT / "report.md").read_text(encoding="utf-8").splitlines():
        if not line.strip():
            continue
        if line.startswith(("## 3. ", "## 7. ")):
            story.append(PageBreak())
        if line.startswith("The class and interaction views"):
            story.extend([
                Paragraph(inline(line), styles["ReportBody"]),
                Paragraph("Implemented class relationships", styles["ReportHeading"]),
                class_view(),
                Paragraph("Figure 1. Presentation adapters and the main service and extension interfaces.", styles["ReportBody"]),
            ])
            continue
        if line.startswith("## 4. "):
            story.extend([
                PageBreak(),
                Paragraph("Remediation review interaction", styles["ReportHeading"]),
                review_sequence(),
                Paragraph("Figure 2. Scope and coverage restrict eligible actions. Failed model validation preserves the Java order.", styles["ReportBody"]),
                Paragraph("Implemented use cases", styles["ReportHeading"]),
                Paragraph("A local reviewer can open a workspace, import captures and notes, inspect observations, compare profiles, request a remediation review or ask a question, and update coverage through either interface. Local Ollama participates only in optional ranking.", styles["ReportBody"]),
                Spacer(1, 8),
            ])
        if line.startswith("# "):
            text, style = line[2:], "ReportTitle"
        elif line.startswith("## "):
            text, style = line[3:], "ReportHeading"
        elif line.startswith("### "):
            text, style = line[4:], "FeatureHeading"
        elif line.startswith("- "):
            text, style = "• " + line[2:], "ReportBullet"
        else:
            text, style = line, "ReportBody"
        if text == "{{DEMO_INDEX}}":
            raise ValueError("Add the actual demonstration index before generating the report.")
        paragraph = Paragraph(inline(text), styles[style])
        story.append(KeepTogether([paragraph]) if style == "ReportBullet" else paragraph)
    document = SimpleDocTemplate(str(REPORT / "stage2-report.pdf"), pagesize=letter,
                                 leftMargin=54, rightMargin=54, topMargin=62, bottomMargin=48,
                                 title="HuntAssist Stage 2 Implementation Report", subject="EECS 3311 Software Design")
    document.build(story, onFirstPage=page_decoration, onLaterPages=page_decoration)


if __name__ == "__main__":
    main()

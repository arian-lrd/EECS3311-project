package ca.yorku.eecs3311.huntassist.review;

import ca.yorku.eecs3311.huntassist.application.AppException;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

final class NoteParser {
    private static final Pattern MARKDOWN_HEADING = Pattern.compile("^ {0,3}#{1,6}\\s+(.+?)\\s*#*\\s*$");
    private static final Pattern FENCE = Pattern.compile("^ {0,3}(\\x60{3,}|~{3,})(.*)$");
    private static final int MAX_SECTIONS = 256;
    private static final int MAX_BODY = 4_000;

    record Section(String heading, String body) {}

    List<Section> parse(String source, String text) {
        String suffix = source.substring(source.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        return switch (suffix) {
            case "md", "markdown" -> markdown(text);
            case "html", "htm" -> html(text);
            case "txt" -> List.copyOf(append(new ArrayList<>(), "Notes", text));
            default -> throw AppException.invalid("Use a Markdown, HTML, or plain-text note.");
        };
    }

    private List<Section> markdown(String text) {
        List<Section> sections = new ArrayList<>();
        String heading = "Notes";
        String openFence = "";
        StringBuilder body = new StringBuilder();
        for (String line : text.split("\\R", -1)) {
            var fence = FENCE.matcher(line);
            if (fence.matches()) {
                String marker = fence.group(1);
                if (openFence.isEmpty()) {
                    openFence = marker;
                } else if (marker.charAt(0) == openFence.charAt(0)
                        && marker.length() >= openFence.length() && fence.group(2).isBlank()) {
                    openFence = "";
                }
                continue;
            }
            if (!openFence.isEmpty()) {
                continue;
            }
            var title = MARKDOWN_HEADING.matcher(line);
            if (title.matches()) {
                append(sections, heading, body.toString());
                heading = title.group(1);
                body.setLength(0);
            } else {
                body.append(line).append('\n');
            }
        }
        return List.copyOf(append(sections, heading, body.toString()));
    }

    private List<Section> html(String text) {
        Element page = Jsoup.parse(text).body();
        page.select("script, style, template, noscript, iframe, object, pre").remove();
        List<Section> sections = new ArrayList<>();
        String heading = "Notes";
        StringBuilder body = new StringBuilder();
        for (Element element : page.select("h1,h2,h3,h4,h5,h6,p,li,dt,dd,blockquote,td,th")) {
            if (element.normalName().matches("h[1-6]")) {
                append(sections, heading, body.toString());
                heading = element.text();
                body.setLength(0);
                continue;
            }
            if (element.children().stream().anyMatch(child -> child.normalName().matches("p|li|blockquote|ul|ol"))) {
                body.append(element.ownText()).append('\n');
            } else {
                body.append(element.text()).append('\n');
            }
        }
        append(sections, heading, body.toString());
        return sections.isEmpty()
                ? List.copyOf(append(sections, "Notes", page.text()))
                : List.copyOf(sections);
    }

    private List<Section> append(List<Section> sections, String heading, String text) {
        String body = clean(text).strip();
        if (body.isEmpty()) {
            return sections;
        }
        String label = clean(heading).strip();
        label = label.isEmpty() ? "Notes" : abbreviate(label, 165);
        int part = 1;
        while (!body.isEmpty()) {
            if (sections.size() >= MAX_SECTIONS) {
                throw AppException.invalid("Split the note into smaller files (up to 256 sections each).");
            }
            int end = Math.min(body.length(), MAX_BODY);
            if (end < body.length()) {
                int lineBreak = Math.max(body.lastIndexOf('\n', end), body.lastIndexOf(' ', end));
                if (lineBreak > MAX_BODY / 2) {
                    end = lineBreak;
                } else if (Character.isHighSurrogate(body.charAt(end - 1))) {
                    end--;
                }
            }
            String sectionLabel = part == 1 ? label : label + " (" + part + ")";
            sections.add(new Section(sectionLabel, body.substring(0, end).strip()));
            body = body.substring(end).strip();
            part++;
        }
        return sections;
    }

    static String clean(String value) {
        return value.replaceAll("[\\p{Cc}\\p{Cf}&&[^\\r\\n\\t]]", "");
    }

    static String abbreviate(String value, int maximum) {
        if (value.length() <= maximum) {
            return value;
        }
        int end = maximum - 1;
        if (Character.isHighSurrogate(value.charAt(end - 1))) {
            end--;
        }
        return value.substring(0, end) + "…";
    }
}

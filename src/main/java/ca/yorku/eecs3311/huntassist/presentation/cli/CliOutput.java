package ca.yorku.eecs3311.huntassist.presentation.cli;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class CliOutput {
    enum Format { TABLE, JSON }

    private final JsonMapper json = JsonMapper.builder().findAndAddModules().build();
    private final PrintWriter out;
    private final PrintWriter err;

    CliOutput(PrintWriter out, PrintWriter err) {
        this.out = out;
        this.err = err;
    }

    void success(String command, Format format, Object data) {
        if (format == Format.JSON) {
            envelope(command, "ok", data, List.of());
        } else {
            JsonNode value = json.valueToTree(data);
            if (value.isArray()) {
                printRows(value);
            } else if (value.isObject()) {
                value.properties().forEach(entry -> out.printf("%-18s %s%n", entry.getKey(), cell(entry.getValue())));
            } else {
                out.println(cell(value));
            }
        }
        out.flush();
    }

    void error(String command, Format format, int code, String message) {
        if (format == Format.JSON) {
            envelope(command, "error", null, List.of(Map.of("code", code, "message", message)));
        }
        err.println(message);
        err.flush();
    }

    void progress(String message) {
        err.println(message);
        err.flush();
    }

    private void envelope(String command, String status, Object data, List<?> errors) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("schemaVersion", 1);
        result.put("command", command);
        result.put("status", status);
        result.put("data", data);
        result.put("warnings", List.of());
        result.put("errors", errors);
        out.println(json.writeValueAsString(result));
        out.flush();
    }

    private void printRows(JsonNode rows) {
        if (rows.isEmpty()) {
            out.println("No records.");
            return;
        }
        JsonNode first = rows.get(0);
        if (!first.isObject()) {
            rows.forEach(row -> out.println(cell(row)));
            return;
        }
        List<String> columns = new ArrayList<>(first.propertyNames());
        List<List<String>> values = new ArrayList<>();
        for (JsonNode row : rows) {
            values.add(columns.stream().map(column -> cell(row.path(column))).toList());
        }
        int[] widths = columns.stream().mapToInt(String::length).toArray();
        for (List<String> row : values) {
            for (int i = 0; i < row.size(); i++) {
                widths[i] = Math.max(widths[i], Math.min(48, row.get(i).length()));
            }
        }
        printRow(columns, widths);
        values.forEach(row -> printRow(row, widths));
    }

    private void printRow(List<String> values, int[] widths) {
        for (int i = 0; i < values.size(); i++) {
            String value = values.get(i);
            if (value.length() > widths[i]) {
                int end = Math.min(widths[i] - 1, value.length());
                if (end > 0 && Character.isHighSurrogate(value.charAt(end - 1))) {
                    end--;
                }
                value = value.substring(0, end) + "…";
            }
            if (i > 0) {
                out.print("  ");
            }
            out.printf("%-" + widths[i] + "s", value);
        }
        out.println();
    }

    private static String cell(JsonNode node) {
        if (node.isNull() || node.isMissingNode()) {
            return "—";
        }
        String text = node.isString() ? node.asString() : node.toString();
        return text.replaceAll("[\\p{Cc}\\p{Cf}\\p{Zl}\\p{Zp}]", " ");
    }
}

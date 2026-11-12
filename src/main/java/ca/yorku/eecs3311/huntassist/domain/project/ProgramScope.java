package ca.yorku.eecs3311.huntassist.domain.project;

import java.net.IDN;
import java.util.List;
import java.util.Locale;

public record ProgramScope(List<String> includes, List<String> excludes) {
    public enum Decision { IN_SCOPE, OUT_OF_SCOPE, UNRESOLVED }

    public ProgramScope {
        includes = normalizeRules(includes);
        excludes = normalizeRules(excludes);
        if (includes.isEmpty()) {
            throw new IllegalArgumentException("Enter at least one allowed host.");
        }
    }

    public Decision classify(String host) {
        final String normalized;
        try {
            normalized = normalizeHost(host);
        } catch (IllegalArgumentException e) {
            return Decision.UNRESOLVED;
        }
        if (excludes.stream().anyMatch(rule -> matches(rule, normalized))) {
            return Decision.OUT_OF_SCOPE;
        }
        return includes.stream().anyMatch(rule -> matches(rule, normalized))
                ? Decision.IN_SCOPE : Decision.OUT_OF_SCOPE;
    }

    private static List<String> normalizeRules(List<String> rules) {
        if (rules == null || rules.size() > 100) {
            throw new IllegalArgumentException("Enter up to 100 host rules per list.");
        }
        return rules.stream().map(ProgramScope::normalizeRule).distinct().sorted().toList();
    }

    private static String normalizeRule(String rule) {
        if (rule == null) {
            throw new IllegalArgumentException("Each rule must be a host or *.host.");
        }
        String value = rule.strip();
        if (value.startsWith("*.")) {
            String base = normalizeHost(value.substring(2));
            if (!base.contains(".") || base.matches("[0-9.]+")) {
                throw new IllegalArgumentException("A wildcard must specify a domain name.");
            }
            return "*." + base;
        }
        return normalizeHost(value);
    }

    private static String normalizeHost(String host) {
        if (host == null || host.isBlank() || host.length() > 254) {
            throw new IllegalArgumentException("Enter a valid host name without a URL or port.");
        }
        try {
            String ascii = IDN.toASCII(host, IDN.USE_STD3_ASCII_RULES).toLowerCase(Locale.ROOT);
            String result = ascii.endsWith(".") ? ascii.substring(0, ascii.length() - 1) : ascii;
            if (result.isEmpty() || result.length() > 253) {
                throw new IllegalArgumentException();
            }
            return result;
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Enter a valid host name without a URL or port.");
        }
    }

    private static boolean matches(String rule, String host) {
        return rule.startsWith("*.") ? host.endsWith(rule.substring(1)) : host.equals(rule);
    }
}

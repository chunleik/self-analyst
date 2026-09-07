package com.selfanalyst.events.query;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class AqlParser {
    private static final Pattern FUNC_PATTERN = Pattern.compile("(\\w+)\\s*=\\s*(\\w+)\\((.*)\\)");
    private static final Pattern VAR_PATTERN = Pattern.compile("(\\w+)\\s*=\\s*(\\w+)");
    // RETURN = {"key": var, "key2": var2, ...}
    private static final Pattern RETURN_JSON = Pattern.compile("(\\w+)\\s*=\\s*\\{([^{}]+)\\}");
    // variable = []
    private static final Pattern EMPTY_ARRAY = Pattern.compile("(\\w+)\\s*=\\s*\\[\\]");
    // variable = [elem1, elem2, ...]
    private static final Pattern ARRAY_PATTERN = Pattern.compile("(\\w+)\\s*=\\s*\\[(.+)\\]");

    public record ParsedStatement(String variable, String functionName, String args) {}

    public ParsedStatement parse(String line) {
        String trimmed = line.trim();

        // Try RETURN = {"key": var, ...}
        Matcher rm = RETURN_JSON.matcher(trimmed);
        if (rm.matches()) {
            return new ParsedStatement(rm.group(1), "{}", rm.group(2).trim());
        }

        // Try function call: variable = func(args)
        Matcher fm = FUNC_PATTERN.matcher(trimmed);
        if (fm.matches()) {
            return new ParsedStatement(fm.group(1), fm.group(2), fm.group(3));
        }

        // Try empty array: variable = []
        Matcher em = EMPTY_ARRAY.matcher(trimmed);
        if (em.matches()) {
            return new ParsedStatement(em.group(1), "[]", "");
        }

        // Try array literal: variable = [elem1, elem2, ...]
        Matcher am = ARRAY_PATTERN.matcher(trimmed);
        if (am.matches()) {
            return new ParsedStatement(am.group(1), "[]", am.group(2).trim());
        }

        // Try simple assignment: variable = other_variable (e.g., RETURN = window_events)
        Matcher vm = VAR_PATTERN.matcher(trimmed);
        if (vm.matches()) {
            return new ParsedStatement(vm.group(1), "", vm.group(2));
        }

        throw new IllegalArgumentException("Invalid AQL statement: " + line);
    }
}

package com.selfanalyst.tools;

import com.selfanalyst.events.query.AqlInterpreter;
import com.selfanalyst.events.query.AqlLexer;
import com.selfanalyst.events.query.AqlParser;

import java.util.HashSet;
import java.util.Set;

/** Model-tool boundary only; the local interpreter and its tag semantics remain unchanged. */
final class AqlPrivacyGuard {
    private static final Set<String> PROTECTED = Set.of("app", "title", "context_title", "url_host", "private_browsing");
    private static final int MAX_DEPTH = 64;
    private final AqlInterpreter syntax = new AqlInterpreter(null);
    private final Set<String> variables = new HashSet<>();

    static final class UnsafeQueryException extends IllegalArgumentException {}

    static void validate(String query) {
        new AqlPrivacyGuard().validateStatements(query);
    }

    private void validateStatements(String query) {
        var parser = new AqlParser();
        AqlParser.ParsedStatement returned = null;
        for (String line : new AqlLexer().tokenize(query)) {
            var statement = parser.parse(line);
            if ("RETURN".equals(statement.variable())) {
                returned = statement; // Only the final RETURN is executed, after all assignments.
            } else if (!"{}".equals(statement.functionName())) {
                validateStatement(statement);
                variables.add(statement.variable());
            }
        }
        if (returned != null) validateStatement(returned);
    }

    private void validateStatement(AqlParser.ParsedStatement statement) {
        String name = statement.functionName();
        if (name.isEmpty() || "[]".equals(name) || "{}".equals(name)) return;
        if (!syntax.supportsFunction(name)) throw new UnsafeQueryException();
        validateCall(name, statement.args(), 0);
    }

    private void validateCall(String name, String arguments, int depth) {
        if (depth > MAX_DEPTH) throw new UnsafeQueryException();
        String[] args = AqlInterpreter.splitArgs(arguments);
        for (String argument : args) {
            String nested = nestedName(argument);
            if (nested != null) {
                String value = argument.trim();
                validateCall(nested, value.substring(value.indexOf('(') + 1, value.length() - 1).trim(), depth + 1);
            }
        }
        if (!"tag".equals(name)) return;
        if (args.length < 2) throw new UnsafeQueryException();
        String tags = tagLiteral(args[1]);
        for (String pair : tags.split(",")) {
            String[] parts = pair.split(":", 2);
            if (parts.length == 2 && PROTECTED.contains(parts[0].trim())) throw new UnsafeQueryException();
        }
    }

    /** Matches resolveArg's nested-call recognition, rather than searching inside strings. */
    private String nestedName(String argument) {
        String value = argument.trim();
        int opening = value.indexOf('(');
        if (opening < 0 || !value.endsWith(")")) return null;
        String name = value.substring(0, opening).trim();
        return syntax.supportsFunction(name) ? name : null;
    }

    private String tagLiteral(String argument) {
        String value = argument.trim();
        if (nestedName(value) != null) throw new UnsafeQueryException();
        if ((value.startsWith("\"") && value.endsWith("\""))
                || (value.startsWith("'") && value.endsWith("'"))) {
            if (value.length() < 2) throw new UnsafeQueryException();
            return AqlInterpreter.trimQuotes(value);
        }
        // Lists and event-list variables cannot safely provide a tag string. The interpreter
        // also accepts unquoted key:value text as a literal fallback; preserve that syntax.
        if (value.startsWith("[") || variables.contains(value) || !value.contains(":")) {
            throw new UnsafeQueryException();
        }
        return value;
    }
}

package com.selfanalyst.events.query;

import com.selfanalyst.events.model.Event;
import com.selfanalyst.events.query.function.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;

public class AqlInterpreter {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final AqlLexer lexer = new AqlLexer();
    private final AqlParser parser = new AqlParser();
    private final Map<String, AqlFunction> functions = new HashMap<>();
    private final Map<String, List<Event>> variables = new HashMap<>();
    private final AqlContext ctx;

    public AqlInterpreter(AqlContext ctx) {
        this.ctx = ctx;
        registerDefaults();
    }

    private void registerDefaults() {
        functions.put("query_bucket", new QueryBucket());
        functions.put("find_bucket", new FindBucket());
        functions.put("filter_keyvals", new FilterKeyvals());
        functions.put("filter_keyvals_regex", new FilterKeyvalsRegex());
        functions.put("filter_period_intersect", new FilterPeriodIntersect());
        functions.put("merge_events_by_keys", new MergeEventsByKeys());
        functions.put("period_union", new PeriodUnion());
        functions.put("union_no_overlap", new UnionNoOverlap());
        functions.put("flood", new Flood());
        functions.put("heartbeat_merge", new HeartbeatMerge());
        functions.put("heartbeat_reduce", new HeartbeatReduce());
        functions.put("sort_by_duration", new SortByDuration());
        functions.put("sort_by_timestamp", new SortByTimestamp());
        functions.put("limit_events", new LimitEvents());
        functions.put("sum_durations", new SumDurations());
        functions.put("chunk_events_by_key", new ChunkEventsByKey());
        functions.put("categorize", new Categorize());
        functions.put("tag", new Tag());
        functions.put("split_url_events", new SplitUrlEvents());
        functions.put("concat", new Concat());
    }

    public Map<String, Object> execute(String aqlQuery) {
        variables.clear();
        List<String> lines = lexer.tokenize(aqlQuery);
        AqlParser.ParsedStatement returnStmt = null;

        for (String line : lines) {
            var stmt = parser.parse(line);
            if (stmt.variable().equals("RETURN")) {
                returnStmt = stmt;
            } else if (stmt.functionName().equals("[]")) {
                // variable = [] or variable = [elem1, elem2, ...]
                if (stmt.args().isEmpty()) {
                    variables.put(stmt.variable(), List.of());
                } else {
                    // variable = [elem1, elem2, ...] (not commonly used, treat as empty)
                    variables.put(stmt.variable(), List.of());
                }
            } else if (stmt.functionName().equals("{}")) {
                // RETURN = {"key": var, ...} — only valid as final RETURN, skip intermediate
            } else if (stmt.functionName().isEmpty()) {
                // Simple assignment: x = y
                List<Event> source = variables.getOrDefault(stmt.args(), List.of());
                variables.put(stmt.variable(), source);
            } else {
                AqlFunction func = functions.get(stmt.functionName());
                if (func == null) throw new IllegalArgumentException("Unknown AQL function: " + stmt.functionName());

                // Parse args: first arg may be event list (variable) or value (string/array/nested call)
                String[] rawArgs = splitArgs(stmt.args());
                List<Event> input = List.of();
                Map<String, Object> params = new LinkedHashMap<>();
                int paramIdx = 0;

                if (rawArgs.length > 0) {
                    Object first = resolveArg(rawArgs[0], variables, functions, ctx);
                    List<Event> eventList = asNonEmptyEventList(first);
                    if (eventList != null) {
                        input = eventList;
                    } else {
                        params.put(String.valueOf(paramIdx++), first);
                    }
                    for (int i = 1; i < rawArgs.length; i++) {
                        params.put(String.valueOf(paramIdx++), resolveArg(rawArgs[i], variables, functions, ctx));
                    }
                }
                List<Event> output = func.apply(input, params, ctx);
                variables.put(stmt.variable(), output);
            }
        }

        if (returnStmt == null) {
            return Map.of("error", "No RETURN statement in AQL query");
        }

        String returnVar = returnStmt.args().trim();
        // Handle RETURN = func(args) and RETURN = {"key": var}
        if (!returnStmt.functionName().isEmpty() && !returnStmt.functionName().equals("{}")) {
            // RETURN = func(args) — execute inline
            AqlFunction func = functions.get(returnStmt.functionName());
            if (func != null) {
                String[] rawArgs = splitArgs(returnVar);
                List<Event> input = List.of();
                Map<String, Object> params = new LinkedHashMap<>();
                int paramIdx = 0;
                if (rawArgs.length > 0) {
                    Object first = resolveArg(rawArgs[0], variables, functions, ctx);
                    List<Event> eventList = asNonEmptyEventList(first);
                    if (eventList != null) {
                        input = eventList;
                    } else {
                        params.put(String.valueOf(paramIdx++), first);
                    }
                    for (int i = 1; i < rawArgs.length; i++) {
                        params.put(String.valueOf(paramIdx++), resolveArg(rawArgs[i], variables, functions, ctx));
                    }
                }
                List<Event> result = func.apply(input, params, ctx);
                return buildResponse(result);
            }
        }
        if (returnStmt.functionName().equals("{}")) {
            // RETURN = {"key": var, "key2": var2, ...}
            return buildObjectResponse(returnVar, variables);
        }
        List<Event> result = variables.getOrDefault(returnVar, List.of());
        return buildResponse(result);
    }

    Map<String, Object> parseParams(String args) {
        Map<String, Object> params = new LinkedHashMap<>();
        if (args == null || args.isBlank()) return params;

        String trimmed = args.trim();

        if (trimmed.startsWith("\"") || trimmed.startsWith("'")) {
            params.put("0", trimQuotes(trimmed));
        } else if (trimmed.startsWith("[")) {
            String inner = trimmed.substring(1, trimmed.length() - 1);
            List<String> values = Arrays.stream(inner.split(","))
                    .map(String::trim)
                    .map(s -> s.replaceAll("^\"|\"$|^'|'$", ""))
                    .toList();
            params.put("0", values);
        } else {
            String[] parts = splitArgs(trimmed);
            for (int i = 0; i < parts.length; i++) {
                params.put(String.valueOf(i), trimQuotes(parts[i].trim()));
            }
        }
        return params;
    }

    public static String[] splitArgs(String s) {
        List<String> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        char quoteChar = 0;
        int bracketDepth = 0;
        int parenDepth = 0;
        for (char c : s.toCharArray()) {
            if ((c == '"' || c == '\'') && !inQuotes) {
                inQuotes = true;
                quoteChar = c;
                current.append(c);
            } else if (c == quoteChar && inQuotes) {
                inQuotes = false;
                quoteChar = 0;
                current.append(c);
            } else if (c == '(' && !inQuotes) { parenDepth++; current.append(c); }
            else if (c == ')' && !inQuotes) { parenDepth--; current.append(c); }
            else if (c == '[' && !inQuotes) { bracketDepth++; current.append(c); }
            else if (c == ']' && !inQuotes) { bracketDepth--; current.append(c); }
            else if (c == ',' && !inQuotes && bracketDepth == 0 && parenDepth == 0) {
                result.add(current.toString().trim());
                current = new StringBuilder();
            } else {
                current.append(c);
            }
        }
        if (!current.isEmpty()) result.add(current.toString().trim());
        return result.toArray(new String[0]);
    }

    public static String trimQuotes(String s) {
        if (s == null) return "";
        s = s.trim();
        if ((s.startsWith("\"") && s.endsWith("\"")) || (s.startsWith("'") && s.endsWith("'"))) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }

    public AqlContext getContext() {
        return ctx;
    }

    /**
     * Resolve a function argument.
     * Returns: List<Event> (var ref or nested func call), String (quoted literal), List<?> (JSON array).
     */
    private Object resolveArg(String raw, Map<String, List<Event>> vars,
                              Map<String, AqlFunction> funcs, AqlContext ctx) {
        String trimmed = raw.trim();
        // Nested function call: func(arg1, arg2, ...)
        if (trimmed.contains("(") && trimmed.endsWith(")")) {
            int parenIdx = trimmed.indexOf('(');
            String nestedName = trimmed.substring(0, parenIdx).trim();
            String nestedArgs = trimmed.substring(parenIdx + 1, trimmed.length() - 1).trim();
            AqlFunction nestedFunc = funcs.get(nestedName);
            if (nestedFunc != null) {
                String[] nr = splitArgs(nestedArgs);
                List<Event> nestedInput = List.of();
                Map<String, Object> np = new LinkedHashMap<>();
                int pIdx = 0;
                for (int i = 0; i < nr.length; i++) {
                    Object val = resolveArg(nr[i], vars, funcs, ctx);
                    List<Event> eventList = asNonEmptyEventList(val);
                    if (i == 0 && eventList != null) {
                        nestedInput = eventList;
                    } else {
                        np.put(String.valueOf(pIdx++), val);
                    }
                }
                return nestedFunc.apply(nestedInput, np, ctx);
            }
        }
        // Quoted string
        if ((trimmed.startsWith("\"") && trimmed.endsWith("\""))
                || (trimmed.startsWith("'") && trimmed.endsWith("'"))) {
            return trimQuotes(trimmed);
        }
        // JSON array: ["a", "b"] or [["cat", "rule"], ...]
        if (trimmed.startsWith("[")) {
            try {
                return MAPPER.readValue(trimmed, java.util.List.class);
            } catch (Exception e) {
                return trimmed;
            }
        }
        // Variable reference → event list
        List<Event> varEvents = vars.get(trimmed);
        if (varEvents != null) {
            return varEvents;
        }
        // Fallback: plain string
        return trimmed;
    }

    private static List<Event> asNonEmptyEventList(Object value) {
        if (!(value instanceof List<?> values) || values.isEmpty()) {
            return null;
        }
        List<Event> events = new ArrayList<>(values.size());
        for (Object item : values) {
            if (!(item instanceof Event event)) {
                return null;
            }
            events.add(event);
        }
        return List.copyOf(events);
    }

    private Map<String, Object> buildObjectResponse(String jsonSpec, Map<String, List<Event>> vars) {
        // Parse "key1": var1, "key2": var2
        Map<String, Object> result = new LinkedHashMap<>();
        // Simple parser: split by ", \""
        String[] pairs = jsonSpec.split(",\\s*\"");
        for (int i = 0; i < pairs.length; i++) {
            String pair = pairs[i].trim();
            if (i > 0) pair = "\"" + pair;
            int colonIdx = pair.indexOf(':');
            if (colonIdx < 0) continue;
            String key = pair.substring(0, colonIdx).trim();
            key = trimQuotes(key);
            String varName = pair.substring(colonIdx + 1).trim();
            List<Event> events = vars.getOrDefault(varName, List.of());
            if (!key.isEmpty()) {
                result.put(key, buildResponse(events).get("rows"));
            }
        }
        // Also add a flattened "rows" for backward compat
        Map<String, Object> response = new LinkedHashMap<>();
        response.putAll(result);
        return response;
    }

    private Map<String, Object> buildResponse(List<Event> result) {
        List<Map<String, Object>> rows = new ArrayList<>();
        double totalDuration = 0;
        for (Event e : result) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("timestamp", e.timestamp().toString());
            row.put("duration", e.duration());
            row.putAll(e.data());
            rows.add(row);
            totalDuration += e.duration();
        }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("rows", rows);
        response.put("total_duration", totalDuration);
        response.put("total_events", result.size());
        return response;
    }
}

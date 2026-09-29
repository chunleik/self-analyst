package com.selfanalyst.ontology;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import java.util.function.IntFunction;

/** Read-only, budgeted graph access. User mutations remain explicit desktop actions. */
public final class OntologyTools {
    private static final ObjectMapper JSON = new ObjectMapper().registerModule(new JavaTimeModule());
    private final OntologyService service;
    public OntologyTools(OntologyService service) { this.service = service; }

    @Tool(description = "Search local knowledge entities: project, activity, application, topic, goal, pattern, improvement. Returns evidence and source coverage. Related entity ID filters accepted relationships. Inferred associations do not prove completion; activity start/end are summary periods, not task duration. Use offset for more results.")
    public String searchOntology(
            @ToolParam(name = "query", description = "Name or alias, empty for all") String query,
            @ToolParam(name = "type", description = "Optional entity type") String type,
            @ToolParam(name = "relatedTo", description = "Optional entity ID") String relatedTo,
            @ToolParam(name = "start", description = "Optional ISO-8601 timestamp with offset") String start,
            @ToolParam(name = "end", description = "Optional ISO-8601 timestamp with offset") String end,
            @ToolParam(name = "offset", description = "Pagination offset, 0 for first page") int offset) {
        return bounded(limit -> service.search(query, type, relatedTo, false, start, end, offset, limit));
    }

    @Tool(description = "Inspect a knowledge entity's incoming/outgoing relations and namespaced evidence. Candidate means ambiguous; confirmed refers only to user-confirmed association, never task completion. Missing evidence must remain unknown. Periods are summary windows and cannot be summed as project effort.")
    public String inspectOntology(
            @ToolParam(name = "id", description = "Entity ID returned by searchOntology") String id,
            @ToolParam(name = "start", description = "Optional ISO-8601 timestamp with offset") String start,
            @ToolParam(name = "end", description = "Optional ISO-8601 timestamp with offset") String end,
            @ToolParam(name = "offset", description = "Relationship pagination offset, initially 0") int offset) {
        return bounded(limit -> service.detail(id, start, end, offset, limit));
    }
    private String bounded(IntFunction<Object> query) {
        try {
            for (int limit = 20; limit >= 1; limit /= 2) {
                String result = JSON.writeValueAsString(query.apply(limit));
                if (result.length() <= 60000) return result;
            }
            return "{\"error\":\"Ontology result exceeds budget\",\"fallbackSuggestion\":\"Use a narrower query\"}";
        } catch (IllegalArgumentException e) {
            return "{\"error\":\"Invalid ontology query\"}";
        } catch (Exception e) {
            return "{\"error\":\"Ontology unavailable\",\"fallbackSuggestion\":\"Use queryWiki\"}";
        }
    }
}

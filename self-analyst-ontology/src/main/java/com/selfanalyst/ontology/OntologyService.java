package com.selfanalyst.ontology;

import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import static com.selfanalyst.ontology.Ontology.*;

/** Serializes user changes and checks live sources before every query. No model or network access. */
public final class OntologyService implements AutoCloseable {
    private final OntologyStore store;
    private final Supplier<Snapshot> sources;
    private Snapshot snapshot;
    private UserState users;
    private boolean closed;
    private Graph cachedGraph;

    public OntologyService(OntologyStore store, Supplier<Snapshot> sources) {
        this.store = Objects.requireNonNull(store);
        this.sources = Objects.requireNonNull(sources);
        this.users = store.users();
        // Persisted projections are never treated as current before the sources are checked.
    }

    public synchronized Map<String, Object> rebuild() {
        refresh(true);
        return statusOf(graph());
    }
    public synchronized Map<String, Object> status() { refresh(false); return statusOf(graph()); }
    private Map<String, Object> statusOf(Graph graph) {
        return Map.of("schemaVersion", 1, "entities", graph.entities.size(), "assertions", graph.assertions.size(),
                "coverage", snapshot.coverage(), "periodMeaning", "summary-period-not-task-duration");
    }
    private void refresh(boolean force) {
        if (closed) throw new IllegalStateException("Ontology is closed");
        Snapshot current;
        try { current = Objects.requireNonNull(sources.get()); }
        catch (RuntimeException e) { throw new IllegalStateException("Ontology sources are unavailable", e); }
        if (force || !current.equals(snapshot)) {
            store.replaceProjection(current);
            snapshot = current;
            cachedGraph = null;
        }
    }

    public synchronized Entity saveEntity(String id, String type, String name, String description, List<String> aliases) {
        refresh(false);
        if (!EDITABLE.contains(type)) throw new IllegalArgumentException("Only projects and topics can be edited");
        Map<String, Entity> entities = new LinkedHashMap<>(users.entities());
        if (id == null || id.isBlank()) id = "manual:" + UUID.randomUUID();
        else {
            id = resolve(id);
            Entity previous = entities.get(id);
            if (previous == null || !previous.editable()) throw new IllegalArgumentException("Source entities are read-only");
            if (!previous.type().equals(type)) throw new IllegalArgumentException("Entity type cannot be changed");
        }
        Entity entity = new Entity(id, type, name, description, aliases, "manual", "", null, null, List.of(), Map.of());
        entities.put(id, entity);
        if (entities.size() > 10000) throw new IllegalArgumentException("User entity limit reached");
        commit(new UserState(entities, users.decisions(), users.redirects()));
        return entity;
    }

    public synchronized void deleteEntity(String id) {
        refresh(false);
        id = resolve(id);
        if (!users.entities().containsKey(id)) throw new IllegalArgumentException("Source entities are read-only");
        final String removed = id;
        Map<String, Entity> entities = new LinkedHashMap<>(users.entities()); entities.remove(id);
        Map<String, Decision> decisions = new LinkedHashMap<>(users.decisions());
        decisions.values().removeIf(d -> d.subject().equals(removed) || d.object().equals(removed));
        Map<String, String> redirects = new LinkedHashMap<>(users.redirects());
        redirects.keySet().removeIf(key -> resolve(key).equals(removed));
        commit(new UserState(entities, decisions, redirects));
    }

    public synchronized Entity merge(String from, String to) {
        refresh(false);
        from = resolve(from); to = resolve(to);
        Entity old = users.entities().get(from), target = users.entities().get(to);
        if (old == null || target == null || from.equals(to) || !old.type().equals(target.type())) {
            throw new IllegalArgumentException("Merge requires two different manual entities of the same type");
        }
        List<String> aliases = Stream.concat(Stream.concat(target.aliases().stream(), old.aliases().stream()), Stream.of(old.name())).distinct().toList();
        Entity merged = new Entity(to, target.type(), target.name(), target.description(), aliases, "manual", "", null, null, List.of(), Map.of());
        Map<String, Entity> entities = new LinkedHashMap<>(users.entities()); entities.remove(from); entities.put(to, merged);
        Map<String, Decision> decisions = new LinkedHashMap<>();
        for (Decision d : users.decisions().values()) {
            Decision next = new Decision(d.subject().equals(from) ? to : d.subject(), d.predicate(), d.object().equals(from) ? to : d.object(), d.action());
            // A rejection survives a conflicting merge rather than silently reviving a rejected relationship.
            decisions.merge(next.key(), next, (a, b) -> "reject".equals(a.action()) ? a : b);
        }
        Map<String, String> redirects = new LinkedHashMap<>(users.redirects());
        String sourceId = from, targetId = to;
        redirects.replaceAll((k, v) -> v.equals(sourceId) ? targetId : v);
        redirects.put(from, to);
        commit(new UserState(entities, decisions, redirects));
        return merged;
    }

    public synchronized void decide(String subject, String predicate, String object, String action) {
        refresh(false);
        Graph graph = graph();
        subject = resolve(subject); object = resolve(object);
        validateRelation(graph.entities.get(subject), predicate, graph.entities.get(object));
        Decision decision = new Decision(subject, predicate, object, action);
        Map<String, Decision> decisions = new LinkedHashMap<>(users.decisions());
        // A confirmed project assignment replaces other confirmed assignments for that activity.
        if ("confirm".equals(action) && "relatedTo".equals(predicate) && "activity".equals(graph.entities.get(subject).type())) {
            String activity = subject;
            decisions.replaceAll((k, d) -> d.subject().equals(activity) && d.predicate().equals("relatedTo")
                    ? new Decision(d.subject(), d.predicate(), d.object(), "reject") : d);
        }
        decisions.put(decision.key(), decision);
        if (decisions.size() > 100000) throw new IllegalArgumentException("Decision limit reached");
        commit(new UserState(users.entities(), decisions, users.redirects()));
    }

    /** Removing a displayed relationship records a rejection, preventing automatic resurrection. */
    public synchronized void removeRelationship(String subject, String predicate, String object) {
        decide(subject, predicate, object, "reject");
    }

    public synchronized Page<Entity> search(String query, String type, String relatedTo, boolean unclassified,
                                             String start, String end, int offset, int limit) {
        refresh(false);
        validatePage(offset, limit);
        bounded(query, 300, "query");
        if (type != null && !type.isBlank() && !TYPES.contains(type)) throw new IllegalArgumentException("Invalid entity type");
        Instant[] period = period(start, end);
        Graph graph = graph();
        String target = relatedTo == null || relatedTo.isBlank() ? null : resolve(relatedTo);
        if (target != null && !graph.entities.containsKey(target)) throw new IllegalArgumentException("Entity not found");
        Set<String> related = new HashSet<>();
        if (target != null) for (Assertion a : graph.assertions.values()) {
            if (!a.status().equals("accepted")) continue;
            if (a.object().equals(target)) related.add(a.subject());
            if (a.subject().equals(target)) related.add(a.object());
        }
        String normalized = normalize(query);
        List<Entity> result = graph.entities.values().stream()
                .filter(e -> type == null || type.isBlank() || type.equals(e.type()))
                .filter(e -> normalized.isBlank() || normalize(e.name() + " " + String.join(" ", e.aliases())).contains(normalized))
                .filter(e -> target == null || related.contains(e.id()))
                .filter(e -> !unclassified || (e.type().equals("activity") && !classification(graph, e.id()).equals("assigned")))
                .filter(e -> overlaps(e.start(), e.end(), period))
                .sorted(Comparator.comparing(Entity::start, Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(Entity::name).thenComparing(Entity::id)).toList();
        return page(result, offset, limit);
    }

    public synchronized Detail detail(String id, String start, String end, int offset, int limit) {
        refresh(false);
        validatePage(offset, limit);
        Instant[] period = period(start, end);
        id = resolve(id);
        Graph graph = graph();
        Entity entity = graph.entities.get(id);
        if (entity == null) throw new IllegalArgumentException("Entity not found");
        String requested = id;
        List<Relation> relations = graph.assertions.values().stream()
                .filter(a -> a.subject().equals(requested) || a.object().equals(requested))
                .filter(a -> overlaps(a.start(), a.end(), period))
                .sorted(Comparator.comparing(Assertion::start, Comparator.nullsLast(Comparator.reverseOrder())).thenComparing(Assertion::key))
                .map(a -> new Relation(a, graph.entities.get(a.subject().equals(requested) ? a.object() : a.subject()))).toList();
        List<String> candidates = graph.assertions.values().stream().filter(a -> a.subject().equals(requested)
                && a.predicate().equals("relatedTo") && a.status().equals("candidate")).map(Assertion::object).sorted().toList();
        return new Detail(entity, page(relations, offset, limit), classification(graph, id), candidates);
    }

    private String classification(Graph graph, String id) {
        if (!graph.entities.get(id).type().equals("activity")) return "not-applicable";
        List<Assertion> matches = graph.assertions.values().stream().filter(a -> a.subject().equals(id) && a.predicate().equals("relatedTo")).toList();
        if (matches.stream().anyMatch(a -> a.status().equals("accepted"))) return "assigned";
        return matches.isEmpty() ? "unclassified" : "ambiguous";
    }

    private Graph graph() {
        if (cachedGraph != null) return cachedGraph;
        Map<String, Entity> entities = new LinkedHashMap<>();
        snapshot.entities().forEach(e -> entities.put(e.id(), e));
        entities.putAll(users.entities());
        Map<String, Assertion> assertions = new LinkedHashMap<>();
        for (Assertion a : snapshot.assertions()) {
            validateRelation(entities.get(a.subject()), a.predicate(), entities.get(a.object()));
            assertions.put(a.key(), a);
        }
        List<Entity> projects = entities.values().stream().filter(e -> e.type().equals("project")).toList();
        List<Entity> topics = entities.values().stream().filter(e -> e.type().equals("topic")).toList();
        for (Entity activity : entities.values()) {
            if (!activity.type().equals("activity")) continue;
            boolean overridden = users.decisions().values().stream().anyMatch(d -> d.subject().equals(activity.id())
                    && d.predicate().equals("relatedTo") && d.action().equals("confirm") && entities.containsKey(d.object()));
            if (!overridden) {
                List<Entity> matches = projects.stream().filter(p -> matches(activity, p))
                        .filter(p -> !rejected(activity.id(), "relatedTo", p.id())).toList();
                for (Entity project : matches) putAutomatic(assertions, activity, "relatedTo", project, matches.size() == 1 ? "accepted" : "candidate");
            }
            for (Entity topic : topics) if (matches(activity, topic)) putAutomatic(assertions, activity, "about", topic, "accepted");
        }
        for (Decision d : users.decisions().values()) {
            Entity subject = entities.get(d.subject()), object = entities.get(d.object());
            if (subject == null || object == null) continue;
            validateRelation(subject, d.predicate(), object);
            if (d.action().equals("reject")) assertions.remove(d.key());
            else assertions.put(d.key(), new Assertion(d.subject(), d.predicate(), d.object(), "confirmed", "manual", "accepted",
                    subject.start(), subject.end(), List.of(new Evidence("user:" + d.key(), "", true))));
        }
        cachedGraph = new Graph(entities, assertions);
        return cachedGraph;
    }
    private boolean rejected(String subject, String predicate, String object) {
        Decision d = users.decisions().get(relationKey(subject, predicate, object));
        return d != null && d.action().equals("reject");
    }
    private void putAutomatic(Map<String, Assertion> assertions, Entity activity, String predicate, Entity target, String status) {
        Assertion a = new Assertion(activity.id(), predicate, target.id(), "inferred", "alias-rule", status,
                activity.start(), activity.end(), activity.evidence());
        assertions.put(a.key(), a);
    }
    static boolean matches(Entity activity, Entity target) {
        String text = normalize(activity.name() + " " + activity.description());
        return Stream.concat(Stream.of(target.name()), target.aliases().stream()).map(Ontology::normalize)
                .filter(alias -> !alias.isBlank()).anyMatch(alias -> {
                    // Latin/digit boundaries avoid Java matching JavaScript; CJK phrases remain matchable in prose.
                    String left = isLatinWord(alias.codePointAt(0)) ? "(?<![\\p{IsLatin}\\p{N}_])" : "";
                    String right = isLatinWord(alias.codePointBefore(alias.length())) ? "(?![\\p{IsLatin}\\p{N}_])" : "";
                    return Pattern.compile(left + Pattern.quote(alias) + right).matcher(text).find();
                });
    }
    private static boolean isLatinWord(int cp) { return Character.isDigit(cp) || cp == '_' || Character.UnicodeScript.of(cp) == Character.UnicodeScript.LATIN; }
    private void commit(UserState next) { store.saveUsers(next); users = next; cachedGraph = null; }
    private String resolve(String id) {
        required(id, 256, "id");
        Set<String> seen = new HashSet<>();
        while (users.redirects().containsKey(id)) {
            if (!seen.add(id)) throw new IllegalStateException("Invalid entity redirects");
            id = users.redirects().get(id);
        }
        return id;
    }
    private <T> Page<T> page(List<T> rows, int offset, int limit) {
        int from = Math.min(offset, rows.size()), to = (int) Math.min((long) from + limit, rows.size());
        return new Page<>(List.copyOf(rows.subList(from, to)), offset, limit, rows.size(), to < rows.size(), snapshot.coverage());
    }
    private static void validatePage(int offset, int limit) {
        if (offset < 0 || offset > 1000000 || limit < 1 || limit > 100) throw new IllegalArgumentException("Invalid pagination");
    }
    private static Instant[] period(String start, String end) {
        try {
            Instant from = start == null || start.isBlank() ? null : Instant.parse(start);
            Instant to = end == null || end.isBlank() ? null : Instant.parse(end);
            if (from != null && to != null && !from.isBefore(to)) throw new IllegalArgumentException("Invalid time range");
            return new Instant[]{from, to};
        } catch (java.time.format.DateTimeParseException e) { throw new IllegalArgumentException("Expected ISO-8601 time with offset"); }
    }
    private static boolean overlaps(Instant start, Instant end, Instant[] query) {
        if (query[0] == null && query[1] == null) return true;
        return start != null && (query[0] == null || end.isAfter(query[0])) && (query[1] == null || start.isBefore(query[1]));
    }
    private record Graph(Map<String, Entity> entities, Map<String, Assertion> assertions) {}
    @Override public synchronized void close() { if (!closed) { closed = true; store.close(); } }
}

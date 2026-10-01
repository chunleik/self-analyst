package com.selfanalyst.ontology;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.time.Instant;
import java.util.*;

/** Typed vocabulary shared by local projections, desktop and read-only agent tools. */
public final class Ontology {
    private Ontology() {}
    public static final Set<String> TYPES = Set.of("project", "activity", "application", "topic", "goal", "pattern", "improvement");
    public static final Set<String> EDITABLE = Set.of("project", "topic");

    public record Evidence(String ref, String text, boolean available) {
        public Evidence {
            ref = required(ref, 512, "evidence reference");
            text = bounded(text, 800, "evidence text");
        }
    }

    public record Entity(String id, String type, String name, String description, List<String> aliases,
                         String source, String sourceRef, Instant start, Instant end,
                         List<Evidence> evidence, Map<String, String> attributes) {
        public Entity {
            id = required(id, 256, "id");
            if (!TYPES.contains(type)) throw new IllegalArgumentException("Invalid entity type");
            name = required(name, 300, "name");
            description = bounded(description, 2000, "description");
            aliases = aliases == null ? List.of() : aliases.stream()
                    .map(a -> required(a, 200, "alias")).distinct().toList();
            if (aliases.size() > 30) throw new IllegalArgumentException("Too many aliases");
            source = required(source, 40, "source");
            sourceRef = bounded(sourceRef, 512, "source reference");
            evidence = evidence == null ? List.of() : List.copyOf(evidence);
            if (evidence.size() > 12) throw new IllegalArgumentException("Too many evidence references");
            attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
            if (attributes.size() > 20 || attributes.entrySet().stream().anyMatch(e -> e.getKey().length() > 80 || e.getValue().length() > 1000)) {
                throw new IllegalArgumentException("Attributes exceed budget");
            }
            if ((start == null) != (end == null) || (start != null && !start.isBefore(end))) {
                throw new IllegalArgumentException("Invalid entity period");
            }
        }
        public boolean editable() { return "manual".equals(source) && EDITABLE.contains(type); }
    }

    public record Assertion(String subject, String predicate, String object, String claimType,
                            String source, String status, Instant start, Instant end, List<Evidence> evidence) {
        public Assertion {
            subject = required(subject, 256, "subject");
            object = required(object, 256, "object");
            predicate = required(predicate, 40, "predicate");
            if (!Set.of("observed", "inferred", "confirmed", "legacy").contains(claimType)) throw new IllegalArgumentException("Invalid claim type");
            if (!Set.of("accepted", "candidate").contains(status)) throw new IllegalArgumentException("Invalid assertion status");
            source = required(source, 40, "assertion source");
            evidence = evidence == null ? List.of() : List.copyOf(evidence);
            if (evidence.size() > 12) throw new IllegalArgumentException("Too many evidence references");
            if ((start == null) != (end == null) || (start != null && !start.isBefore(end))) throw new IllegalArgumentException("Invalid assertion period");
        }
        public String key() { return relationKey(subject, predicate, object); }
    }

    public record Decision(String subject, String predicate, String object, String action) {
        public Decision {
            required(subject, 256, "subject"); required(predicate, 40, "predicate"); required(object, 256, "object");
            if (!Set.of("confirm", "reject").contains(action)) throw new IllegalArgumentException("Invalid decision");
        }
        public String key() { return relationKey(subject, predicate, object); }
    }

    public record Snapshot(List<Entity> entities, List<Assertion> assertions, Map<String, String> coverage) {
        public Snapshot {
            entities = List.copyOf(entities); assertions = List.copyOf(assertions); coverage = Map.copyOf(coverage);
            if (entities.size() > 50000 || assertions.size() > 100000) throw new IllegalArgumentException("Source snapshot exceeds budget");
            Set<String> ids = new HashSet<>();
            Map<String, Entity> byId = new HashMap<>();
            for (Entity e : entities) {
                if ("manual".equals(e.source()) || !ids.add(e.id())) throw new IllegalArgumentException("Invalid source entity identity");
                byId.put(e.id(), e);
            }
            for (Assertion a : assertions) validateRelation(byId.get(a.subject()), a.predicate(), byId.get(a.object()));
        }
        public static Snapshot empty() { return new Snapshot(List.of(), List.of(), Map.of("wiki", "unavailable", "memory", "unavailable")); }
    }

    public record UserState(Map<String, Entity> entities, Map<String, Decision> decisions, Map<String, String> redirects) {
        public UserState {
            entities = Map.copyOf(entities); decisions = Map.copyOf(decisions); redirects = Map.copyOf(redirects);
            if (entities.size() > 10000 || decisions.size() > 100000 || redirects.size() > 100000) throw new IllegalArgumentException("User data exceeds budget");
            for (var entry : entities.entrySet()) {
                if (!entry.getKey().equals(entry.getValue().id()) || !entry.getValue().editable()) throw new IllegalArgumentException("Invalid user entity");
            }
            for (var entry : decisions.entrySet()) {
                if (!entry.getKey().equals(entry.getValue().key())) throw new IllegalArgumentException("Invalid user decision identity");
            }
            for (String id : redirects.keySet()) {
                if (entities.containsKey(id)) throw new IllegalArgumentException("Redirect masks existing entity");
                Set<String> visited = new HashSet<>();
                String target = id;
                while (redirects.containsKey(target)) {
                    if (!visited.add(target)) throw new IllegalArgumentException("Cyclic entity redirect");
                    target = redirects.get(target);
                }
                if (!entities.containsKey(target)) throw new IllegalArgumentException("Dangling entity redirect");
            }
        }
        public static UserState empty() { return new UserState(Map.of(), Map.of(), Map.of()); }
    }

    public record Page<T>(List<T> items, int offset, int limit, int total, boolean hasMore, Map<String, String> coverage) {}
    public record Relation(Assertion assertion, Entity other) {}
    public record Detail(Entity entity, Page<Relation> relations, String classification, List<String> candidateProjectIds) {}

    public static void validateRelation(Entity subject, String predicate, Entity object) {
        if (subject == null || object == null || subject.id().equals(object.id())) throw new IllegalArgumentException("Relationship endpoint unavailable");
        boolean valid = switch (predicate) {
            case "relatedTo" -> (subject.type().equals("activity") && object.type().equals("project"))
                    || (subject.type().equals("pattern") && Set.of("project", "topic").contains(object.type()));
            case "uses" -> subject.type().equals("activity") && object.type().equals("application");
            case "about" -> subject.type().equals("activity") && object.type().equals("topic");
            case "supports" -> subject.type().equals("project") && object.type().equals("goal");
            case "tracks" -> subject.type().equals("improvement") && object.type().equals("goal");
            default -> false;
        };
        if (!valid) throw new IllegalArgumentException("Relationship is not allowed for these entity types");
    }

    public static String relationKey(String subject, String predicate, String object) {
        return stableId("relation", subject + "\n" + predicate + "\n" + object);
    }
    public static String stableId(String namespace, String value) {
        try {
            return namespace + ":" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    public static String normalize(String value) {
        return Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFKC).strip().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }
    public static String required(String value, int max, String field) {
        String s = bounded(value, max, field).strip();
        if (s.isEmpty()) throw new IllegalArgumentException("Missing " + field);
        return s;
    }
    public static String bounded(String value, int max, String field) {
        String s = value == null ? "" : value;
        if (s.codePointCount(0, s.length()) > max || s.indexOf('\0') >= 0) throw new IllegalArgumentException("Invalid " + field);
        return s;
    }
    public static String clip(String value, int max) {
        if (value == null) return "";
        return value.codePointCount(0, value.length()) <= max ? value : value.substring(0, value.offsetByCodePoints(0, max));
    }
}

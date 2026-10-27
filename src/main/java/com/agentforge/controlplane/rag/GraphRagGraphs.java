package com.agentforge.controlplane.rag;

import com.agentforge.controlplane.util.Jsons;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** GraphRAG 抽取结果的解析、合并和查询匹配。不访问模型。 */
public final class GraphRagGraphs {

    public record Entity(String name, String type, String description) {}

    public record Relation(String source, String target, String description) {}

    public record Extract(List<Entity> entities, List<Relation> relations) {}

    private GraphRagGraphs() {}

    public static Extract parse(String text) {
        if (text == null || text.isBlank()) {
            return new Extract(List.of(), List.of());
        }
        String raw = text.strip();
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return new Extract(List.of(), List.of());
        }
        Map<String, Object> body = Jsons.map(raw.substring(start, end + 1));
        List<Entity> entities = new ArrayList<>();
        for (Map<String, Object> item : Jsons.mapList(body.get("entities"))) {
            String name = Jsons.text(item.get("name")).strip();
            if (name.isEmpty()) {
                continue;
            }
            entities.add(new Entity(clip(name, 80), clip(Jsons.text(item.get("type")), 40), clip(Jsons.text(item.get("description")), 300)));
        }
        List<Relation> relations = new ArrayList<>();
        for (Map<String, Object> item : Jsons.mapList(body.get("relations"))) {
            String source = Jsons.text(item.get("source")).strip();
            String target = Jsons.text(item.get("target")).strip();
            if (source.isEmpty() || target.isEmpty()) {
                continue;
            }
            relations.add(new Relation(clip(source, 80), clip(target, 80), clip(Jsons.text(item.get("description")), 300)));
        }
        return new Extract(entities, relations);
    }

    public static Extract merge(List<Extract> parts) {
        Map<String, Entity> entities = new LinkedHashMap<>();
        List<Relation> relations = new ArrayList<>();
        for (Extract part : parts) {
            for (Entity entity : part.entities()) {
                String key = key(entity.name());
                Entity previous = entities.get(key);
                if (previous == null || previous.description().length() < entity.description().length()) {
                    entities.put(key, entity);
                }
            }
            for (Relation relation : part.relations()) {
                boolean exists = relations.stream().anyMatch(item ->
                        key(item.source()).equals(key(relation.source()))
                                && key(item.target()).equals(key(relation.target()))
                                && item.description().equals(relation.description()));
                if (!exists && relations.size() < 80) {
                    relations.add(relation);
                }
            }
        }
        return new Extract(new ArrayList<>(entities.values()), relations);
    }

    /** 问题里点名了实体，或实体名包含问题里的词。 */
    public static List<Entity> match(String query, List<Entity> entities) {
        String haystack = query == null ? "" : query.toLowerCase(Locale.ROOT);
        List<Entity> found = new ArrayList<>();
        for (Entity entity : entities) {
            String name = entity.name().toLowerCase(Locale.ROOT);
            if (name.length() >= 2 && (haystack.contains(name) || name.contains(haystack) && haystack.length() >= 2)) {
                found.add(entity);
            }
        }
        return found;
    }

    public static String key(String name) {
        return name == null ? "" : name.strip().toLowerCase(Locale.ROOT);
    }

    private static String clip(String value, int limit) {
        String text = value == null ? "" : value.strip();
        return text.length() <= limit ? text : text.substring(0, limit);
    }
}

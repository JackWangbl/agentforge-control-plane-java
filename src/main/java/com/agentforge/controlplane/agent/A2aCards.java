package com.agentforge.controlplane.agent;

import com.agentforge.controlplane.domain.Agent;
import com.agentforge.controlplane.web.ApiException;
import io.a2a.spec.AgentSkill;
import io.agentscope.core.a2a.server.card.ConfigurableAgentCard;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 智能体名片里允许手改的部分。调用地址始终由系统填写。 */
public final class A2aCards {

    private A2aCards() {}

    public static Map<String, Object> normalize(Object raw) {
        if (raw == null) {
            return empty();
        }
        if (!(raw instanceof Map<?, ?> map)) {
            throw ApiException.badRequest("A2A 名片必须是 JSON 对象");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        String name = clip(text(map.get("name")), 80);
        String description = clip(text(map.get("description")), 500);
        String version = clip(text(map.get("version")), 40);
        String doc = clip(text(first(map, "documentation_url", "documentationUrl")), 300);
        if (!doc.isBlank() && !doc.startsWith("http://") && !doc.startsWith("https://")) {
            throw ApiException.badRequest("名片文档地址要以 http:// 或 https:// 开头");
        }
        if (!name.isBlank()) {
            out.put("name", name);
        }
        if (!description.isBlank()) {
            out.put("description", description);
        }
        if (!version.isBlank()) {
            out.put("version", version);
        }
        if (!doc.isBlank()) {
            out.put("documentation_url", doc);
        }
        out.put("skills", skillMaps(map.get("skills")));
        return out;
    }

    public static void apply(ConfigurableAgentCard.Builder builder, Agent agent) {
        Map<String, Object> card = agent.getA2aCard() == null ? Map.of() : agent.getA2aCard();
        String name = text(card.get("name"));
        String description = text(card.get("description"));
        String version = text(card.get("version"));
        String doc = text(card.get("documentation_url"));
        builder.name(name.isBlank() ? agent.getName() : name);
        builder.description(description.isBlank() ? fallbackDescription(agent) : description);
        if (version.isBlank()) {
            version = agent.getVersion() == null ? "" : agent.getVersion().strip();
        }
        builder.version(version.isBlank() ? "1.0.0" : version);
        if (!doc.isBlank()) {
            builder.documentationUrl(doc);
        }
        List<AgentSkill> skills = agentSkills(card.get("skills"));
        if (!skills.isEmpty()) {
            builder.skills(skills);
        }
    }

    private static Map<String, Object> empty() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("skills", List.of());
        return out;
    }

    private static List<Map<String, Object>> skillMaps(Object raw) {
        if (raw == null) {
            return List.of();
        }
        if (!(raw instanceof List<?> list)) {
            throw ApiException.badRequest("名片的 skills 要写成数组");
        }
        if (list.size() > 8) {
            throw ApiException.badRequest("名片最多写 8 项能力");
        }
        List<Map<String, Object>> skills = new ArrayList<>();
        int index = 1;
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> map)) {
                throw ApiException.badRequest("每一项能力都要写成对象");
            }
            String name = clip(text(map.get("name")), 80);
            if (name.isBlank()) {
                throw ApiException.badRequest("能力需要填写名称");
            }
            String id = clip(text(map.get("id")), 64);
            if (id.isBlank() || !id.matches("[A-Za-z0-9_-]+")) {
                id = "skill-" + index;
            }
            Map<String, Object> skill = new LinkedHashMap<>();
            skill.put("id", id);
            skill.put("name", name);
            skill.put("description", clip(text(map.get("description")), 400));
            skill.put("examples", strings(map.get("examples"), 5, 160));
            List<String> tags = strings(map.get("tags"), 6, 40);
            if (!tags.isEmpty()) {
                skill.put("tags", tags);
            }
            skills.add(skill);
            index++;
        }
        return skills;
    }

    private static List<AgentSkill> agentSkills(Object raw) {
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        List<AgentSkill> skills = new ArrayList<>();
        int index = 1;
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> map)) {
                continue;
            }
            String name = clip(text(map.get("name")), 80);
            if (name.isBlank()) {
                continue;
            }
            String id = clip(text(map.get("id")), 64);
            if (id.isBlank() || !id.matches("[A-Za-z0-9_-]+")) {
                id = "skill-" + index;
            }
            List<String> tags = strings(map.get("tags"), 6, 40);
            skills.add(new AgentSkill.Builder()
                    .id(id)
                    .name(name)
                    .description(clip(text(map.get("description")), 400))
                    .tags(tags.isEmpty() ? List.of("agent") : tags)
                    .examples(strings(map.get("examples"), 5, 160))
                    .inputModes(List.of("text"))
                    .outputModes(List.of("text"))
                    .build());
            index++;
        }
        return skills;
    }

    private static List<String> strings(Object raw, int max, int width) {
        List<String> out = new ArrayList<>();
        if (raw instanceof String text) {
            if (!text.isBlank()) {
                out.add(clip(text, width));
            }
            return out;
        }
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        for (Object item : list) {
            if (out.size() >= max) {
                break;
            }
            String text = text(item);
            if (!text.isBlank()) {
                out.add(clip(text, width));
            }
        }
        return out;
    }

    private static Object first(Map<?, ?> map, String... keys) {
        for (String key : keys) {
            if (map.containsKey(key) && map.get(key) != null) {
                return map.get(key);
            }
        }
        return null;
    }

    private static String fallbackDescription(Agent agent) {
        String description = agent.getDescription() == null ? "" : agent.getDescription().strip();
        return description.isBlank() ? agent.getName() : description;
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).strip();
    }

    private static String clip(String value, int max) {
        String text = value == null ? "" : value.strip();
        return text.length() <= max ? text : text.substring(0, max);
    }
}

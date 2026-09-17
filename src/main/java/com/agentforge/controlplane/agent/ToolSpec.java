package com.agentforge.controlplane.agent;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 一个工具对模型的自我描述。parameters 是标准的 JSON Schema，
 * 注册进 AgentScope 的 Toolkit 时会原样交给 ToolBase.inputSchema。
 */
public record ToolSpec(String name, String description, Map<String, Object> parameters) {

    public static Map<String, Object> objectSchema(Map<String, Object> properties, String... required) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties == null ? Map.of() : properties);
        if (required.length > 0) {
            schema.put("required", java.util.List.of(required));
        }
        return schema;
    }

    public static Map<String, Object> stringParam(String description) {
        Map<String, Object> param = new LinkedHashMap<>();
        param.put("type", "string");
        param.put("description", description);
        return param;
    }

    public static Map<String, Object> integerParam(String description, Integer defaultValue) {
        Map<String, Object> param = new LinkedHashMap<>();
        param.put("type", "integer");
        param.put("description", description);
        if (defaultValue != null) {
            param.put("default", defaultValue);
        }
        return param;
    }

    public static Map<String, Object> emptySchema() {
        return objectSchema(Map.of());
    }
}

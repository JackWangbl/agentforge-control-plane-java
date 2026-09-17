package com.agentforge.controlplane.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Map;

/** 认证、资源 CRUD、调试台、实验的请求体。字段名保持下划线，对齐前端。 */
public final class ApiDtos {

    private ApiDtos() {}

    public record LoginRequest(@NotBlank String username, @NotBlank String password) {}

    public record TenantCreate(
            @NotBlank @Size(min = 2, max = 60) String slug,
            @NotBlank String name,
            String description) {
        public TenantCreate {
            description = description == null ? "" : description;
        }
    }

    public record UserCreate(
            @NotBlank @Size(min = 2, max = 60) String username,
            String display_name,
            @NotBlank @Size(min = 4, max = 80) String password,
            @NotNull Long role_id,
            Long tenant_id,
            Boolean enabled) {
        public UserCreate {
            display_name = display_name == null ? "" : display_name;
            enabled = enabled == null || enabled;
        }
    }

    public record UserUpdate(String display_name, String password, Long role_id, Long tenant_id, Boolean enabled) {}

    public record McpCreate(
            @NotBlank @Size(min = 2, max = 100) String name,
            @NotBlank @Pattern(regexp = "^(stdio|sse|http|http_stream|streamable_http|opencli)$") String transport,
            String endpoint,
            Boolean enabled,
            Map<String, Object> config) {
        public McpCreate {
            endpoint = endpoint == null ? "" : endpoint;
            enabled = enabled == null || enabled;
            config = config == null ? Map.of() : config;
        }
    }

    public record SkillCreate(
            @NotBlank @Size(min = 2, max = 100) String name,
            String description,
            String source,
            String version,
            String instruction,
            Boolean enabled) {
        public SkillCreate {
            description = description == null ? "" : description;
            source = source == null ? "manual" : source;
            version = version == null ? "1.0.0" : version;
            instruction = instruction == null ? "" : instruction;
            enabled = enabled == null || enabled;
        }
    }

    public record ModelCreate(
            @NotBlank String name,
            @NotBlank String provider,
            @NotBlank String model_id,
            String base_url,
            String api_key,
            String api_key_ref,
            Double temperature,
            Boolean enabled) {
        public ModelCreate {
            base_url = base_url == null ? "" : base_url;
            api_key = api_key == null ? "" : api_key;
            api_key_ref = api_key_ref == null ? "" : api_key_ref;
            temperature = temperature == null ? 0.2 : temperature;
            enabled = enabled == null || enabled;
        }
    }

    public record WorkflowCreate(
            @NotBlank String name,
            String description,
            String status,
            Map<String, Object> graph) {
        public WorkflowCreate {
            description = description == null ? "" : description;
            status = status == null ? "draft" : status;
            graph = graph == null ? Map.of() : graph;
        }
    }

    public record AgentCreate(
            @NotBlank @Size(min = 2, max = 80) String name,
            String description,
            String model_name,
            String status,
            String version,
            String system_prompt,
            List<Long> skill_ids,
            List<Long> mcp_ids,
            List<Long> opencli_ids,
            List<Long> http_agent_ids,
            List<Map<String, Object>> tool_flows,
            Long sandbox_id) {
        public AgentCreate {
            description = description == null ? "" : description;
            model_name = model_name == null ? "" : model_name;
            status = status == null ? "draft" : status;
            version = version == null ? "v1.0.0" : version;
            system_prompt = system_prompt == null ? "" : system_prompt;
            skill_ids = skill_ids == null ? List.of() : skill_ids;
            mcp_ids = mcp_ids == null ? List.of() : mcp_ids;
            opencli_ids = opencli_ids == null ? List.of() : opencli_ids;
            http_agent_ids = http_agent_ids == null ? List.of() : http_agent_ids;
            tool_flows = tool_flows == null ? List.of() : tool_flows;
        }
    }

    public record HttpAgentCreate(
            @NotBlank @Size(min = 2, max = 100) String name,
            String description,
            @NotBlank String protocol,
            @NotBlank String endpoint,
            Map<String, Object> headers,
            String input_field,
            String output_path,
            @Min(5) @Max(120) Integer timeout_seconds,
            Boolean enabled,
            Map<String, Object> config) {
        public HttpAgentCreate {
            description = description == null ? "" : description;
            headers = headers == null ? Map.of() : headers;
            input_field = input_field == null ? "" : input_field;
            output_path = output_path == null ? "" : output_path;
            timeout_seconds = timeout_seconds == null ? 30 : timeout_seconds;
            enabled = enabled == null || enabled;
            config = config == null ? Map.of() : config;
        }
    }

    public record AgentInvoke(
            @NotBlank @Size(max = 20000) String message,
            @Size(max = 120) String session_id) {
        public AgentInvoke {
            session_id = session_id == null ? "" : session_id;
        }
    }

    public record AgentCopy(@Size(max = 80) String name) {}

    public record AgentRename(@NotBlank @Size(min = 2, max = 80) String name) {}

    public record SandboxCreate(
            @NotBlank String name,
            String runtime,
            String cpu_limit,
            String memory_limit,
            @Min(1) @Max(3600) Integer timeout_seconds,
            String network_mode,
            Boolean enabled) {
        public SandboxCreate {
            runtime = runtime == null ? "docker:python:3.11-slim" : runtime;
            cpu_limit = cpu_limit == null ? "1 vCPU" : cpu_limit;
            memory_limit = memory_limit == null ? "1 GiB" : memory_limit;
            timeout_seconds = timeout_seconds == null ? 60 : timeout_seconds;
            network_mode = network_mode == null ? "deny" : network_mode;
            enabled = enabled == null || enabled;
        }
    }

    public record RoleCreate(@NotBlank String name, String description, List<String> permissions) {
        public RoleCreate {
            description = description == null ? "" : description;
            permissions = permissions == null ? List.of() : permissions;
        }
    }

    public record ResourceStatusUpdate(@NotNull Boolean enabled) {}

    public record OpenCliQuery(
            @Pattern(regexp = "^(tabs|query|eval|exec|run)$") String action,
            String target,
            String selector,
            String expression,
            String command) {
        public OpenCliQuery {
            action = action == null ? "query" : action;
            target = target == null ? "" : target;
            selector = selector == null ? "" : selector;
            expression = expression == null ? "" : expression;
            command = command == null ? "" : command;
        }
    }

    public record PlaygroundRun(
            @NotNull Long agent_id,
            Long model_config_id,
            @NotBlank @Size(max = 20000) String message,
            String session_id,
            Long experiment_id,
            @Size(max = 120) String user_key) {}

    public record PlaygroundResume(
            @NotNull Long agent_id,
            Long model_config_id,
            @NotBlank @Size(max = 120) String session_id,
            Long experiment_id,
            @Size(max = 120) String user_key,
            Boolean force_rerun_tools) {
        public PlaygroundResume {
            force_rerun_tools = Boolean.TRUE.equals(force_rerun_tools);
        }
    }

    public record ExperimentCreate(
            @NotBlank @Size(max = 120) String name,
            String description,
            String assignment_unit,
            String assignment_strategy,
            @Min(1) @Max(100) Integer traffic_percent,
            List<Map<String, Object>> variants) {
        public ExperimentCreate {
            description = description == null ? "" : description;
            assignment_unit = assignment_unit == null ? "session" : assignment_unit;
            assignment_strategy = assignment_strategy == null ? "user_hash" : assignment_strategy;
            traffic_percent = traffic_percent == null ? 100 : traffic_percent;
            variants = variants == null ? List.of() : variants;
        }
    }

    public record ExperimentUpdate(
            String name,
            String description,
            String assignment_unit,
            String assignment_strategy,
            @Min(1) @Max(100) Integer traffic_percent,
            List<Map<String, Object>> variants) {}

    public record ExperimentAssign(String unit_key, @Size(max = 120) String session_id, @Size(max = 120) String user_key) {
        public ExperimentAssign {
            unit_key = unit_key == null ? "" : unit_key;
        }
    }

    public record ExperimentCompare(
            Long dataset_id,
            List<String> prompts,
            String scorer,
            @Min(1) @Max(12) Integer case_limit) {
        public ExperimentCompare {
            prompts = prompts == null ? List.of() : prompts;
            scorer = scorer == null ? "contains" : scorer;
            case_limit = case_limit == null ? 6 : case_limit;
        }
    }
}

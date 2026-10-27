package com.agentforge.controlplane.agent;

import com.agentforge.controlplane.domain.Agent;
import com.agentforge.controlplane.domain.AgentRelease;
import com.agentforge.controlplane.domain.ModelConfig;
import com.agentforge.controlplane.domain.Skill;
import com.agentforge.controlplane.repo.AgentReleaseRepository;
import com.agentforge.controlplane.repo.AgentRepository;
import com.agentforge.controlplane.repo.ModelConfigRepository;
import com.agentforge.controlplane.util.Jsons;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 把智能体当前的提示词、Skill 和工具清单抄成一份发布版。正式调用读这份，草稿修改不影响它。 */
@Service
public class AgentReleaseService {

    private static final Logger log = LoggerFactory.getLogger(AgentReleaseService.class);

    private final AgentReleaseRepository releases;
    private final AgentRepository agents;
    private final ModelConfigRepository models;
    private final ToolRuntime tools;

    public AgentReleaseService(AgentReleaseRepository releases, AgentRepository agents,
                               ModelConfigRepository models, ToolRuntime tools) {
        this.releases = releases;
        this.agents = agents;
        this.models = models;
        this.tools = tools;
    }

    public record Published(AgentRelease release, boolean created) {}

    public record View(Integer revision, boolean drift) {}

    @Transactional
    public Published publish(Agent agent) {
        Map<String, Object> snapshot = capture(agent);
        String fingerprint = AgentReleasePins.fingerprint(snapshot);
        AgentRelease current = current(agent);
        if (current != null && fingerprint.equals(current.getFingerprint())) {
            return new Published(current, false);
        }
        int next = releases.findFirstByAgentIdOrderByRevisionDesc(agent.getId())
                .map(row -> row.getRevision() + 1)
                .orElse(1);
        AgentRelease row = new AgentRelease();
        row.setTenantId(agent.getTenantId() == null ? 1L : agent.getTenantId());
        row.setAgentId(agent.getId());
        row.setRevision(next);
        row.setFingerprint(fingerprint);
        row.setSnapshotJson(Jsons.json(snapshot));
        releases.save(row);
        agent.setPublishedReleaseId(row.getId());
        agents.save(agent);
        return new Published(row, true);
    }

    @Transactional
    public AgentRelease ensurePublished(Agent agent) {
        AgentRelease current = current(agent);
        if (current != null) {
            return current;
        }
        return publish(agent).release();
    }

    @Transactional
    public void backfillMissing() {
        for (Agent agent : agents.findAll()) {
            if (agent.getId() == null || current(agent) != null) {
                continue;
            }
            try {
                publish(agent);
            } catch (RuntimeException ex) {
                log.warn("智能体 {} 没能生成发布版：{}", agent.getId(), ex.getMessage());
            }
        }
    }

    public AgentRelease current(Agent agent) {
        if (agent == null || agent.getPublishedReleaseId() == null || agent.getId() == null) {
            return null;
        }
        return releases.findByIdAndAgentId(agent.getPublishedReleaseId(), agent.getId()).orElse(null);
    }

    public View view(Agent agent) {
        AgentRelease release = current(agent);
        if (release == null) {
            return new View(null, false);
        }
        String live = AgentReleasePins.fingerprint(capture(agent));
        return new View(release.getRevision(), !live.equals(release.getFingerprint()));
    }

    public void deleteForAgent(Long agentId) {
        if (agentId != null) {
            releases.deleteByAgentId(agentId);
        }
    }

    public ModelConfig resolveModel(AgentRelease release, ModelConfig fallback) {
        String name = AgentReleasePins.modelName(AgentReleasePins.readSnapshot(release.getSnapshotJson())).strip();
        if (name.isEmpty()) {
            return fallback;
        }
        return models.findByName(name).filter(ModelConfig::isEnabled).orElse(fallback);
    }

    public Map<String, Object> capture(Agent agent) {
        String prompt = agent.getSystemPrompt() == null ? "" : agent.getSystemPrompt().strip();
        if (prompt.isEmpty()) {
            String duty = agent.getDescription() == null || agent.getDescription().isBlank()
                    ? "你是一名专业的企业助手。" : agent.getDescription();
            prompt = "你是" + agent.getName() + "。" + duty;
        }
        List<Map<String, Object>> skills = new ArrayList<>();
        for (Skill skill : tools.selectedSkills(agent)) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", skill.getId());
            item.put("name", skill.getName());
            item.put("instruction", tools.skillInstruction(skill));
            skills.add(item);
        }
        List<Map<String, Object>> pinnedTools = new ArrayList<>();
        for (ToolSpec spec : tools.liveAgentTools(agent)) {
            if (AgentReleasePins.unpinnedTool(spec.name())) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", spec.name());
            item.put("description", spec.description() == null ? "" : spec.description());
            item.put("parameters", spec.parameters() == null ? ToolSpec.emptySchema() : spec.parameters());
            pinnedTools.add(item);
        }
        return AgentReleasePins.snapshot(prompt, agent.getModelName(), skills, pinnedTools, agent.getToolFlows());
    }
}

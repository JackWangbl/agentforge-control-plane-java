package com.agentforge.controlplane.web;

import com.agentforge.controlplane.access.CurrentUser;
import com.agentforge.controlplane.access.RequirePermission;
import com.agentforge.controlplane.agent.MultiAgentDebugService;
import com.agentforge.controlplane.dto.ApiDtos;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class MultiAgentController {

    private final MultiAgentDebugService debugService;

    public MultiAgentController(MultiAgentDebugService debugService) {
        this.debugService = debugService;
    }

    @RequirePermission({"session:write", "agent:write"})
    @PostMapping("/api/workflows/{id}/debug")
    public Map<String, Object> debug(CurrentUser user, @PathVariable Long id, @RequestBody Map<String, Object> payload) {
        String message = payload.get("message") == null ? "" : String.valueOf(payload.get("message")).strip();
        if (message.isEmpty()) {
            throw ApiException.badRequest("请输入要发送的内容");
        }
        if (message.length() > 20000) {
            throw ApiException.badRequest("消息过长");
        }
        String sessionId = payload.get("session_id") == null ? "" : String.valueOf(payload.get("session_id"));
        String focus = payload.get("focus_node_id") == null ? "" : String.valueOf(payload.get("focus_node_id"));
        return debugService.debug(user, id, message, sessionId, focus);
    }

    @RequirePermission("workflow:read")
    @GetMapping("/api/workflows/{id}/interface")
    public Map<String, Object> interfaceCard(CurrentUser user, @PathVariable Long id) {
        return debugService.interfaceCard(user, id);
    }

    @RequirePermission({"session:write", "agent:write"})
    @PostMapping("/api/workflows/{id}/invoke")
    public Map<String, Object> invoke(CurrentUser user, @PathVariable Long id,
                                      @Valid @RequestBody ApiDtos.WorkflowInvoke payload) {
        return debugService.invoke(user, id, payload.message(), payload.session_id());
    }
}

package com.agentforge.controlplane.web;

import com.agentforge.controlplane.access.CurrentUser;
import com.agentforge.controlplane.access.RequirePermission;
import com.agentforge.controlplane.access.ResourceAccessService;
import com.agentforge.controlplane.access.ResourceKind;
import com.agentforge.controlplane.agent.A2aGateway;
import com.agentforge.controlplane.domain.Agent;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.Map;

/** 把平台里的智能体暴露成 A2A：卡片用于发现，POST 用于 JSON-RPC 调用。 */
@RestController
public class A2aController {

    private final ResourceAccessService access;
    private final A2aGateway gateway;

    public A2aController(ResourceAccessService access, A2aGateway gateway) {
        this.access = access;
        this.gateway = gateway;
    }

    @RequirePermission({"session:write", "agent:write"})
    @GetMapping(value = "/api/a2a/agents/{id}/.well-known/agent-card.json", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> card(CurrentUser user, @PathVariable Long id) {
        Agent agent = access.getRow(user, ResourceKind.AGENT, id);
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(gateway.cardJson(agent));
    }

    @RequirePermission({"session:write", "agent:write"})
    @PostMapping(value = "/api/a2a/agents/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public void message(CurrentUser user, @PathVariable Long id, @RequestBody String body,
                        @RequestHeader Map<String, String> headers, HttpServletResponse response) throws IOException {
        Agent agent = access.getRow(user, ResourceKind.AGENT, id);
        gateway.write(agent, body, headers, response);
    }
}

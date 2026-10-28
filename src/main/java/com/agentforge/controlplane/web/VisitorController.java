package com.agentforge.controlplane.web;

import com.agentforge.controlplane.access.CurrentUser;
import com.agentforge.controlplane.access.RequirePermission;
import com.agentforge.controlplane.observability.ClientIpResolver;
import com.agentforge.controlplane.observability.IpBlacklistService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/dashboard/visitors")
@RequirePermission("tenant:admin")
public class VisitorController {

    private final IpBlacklistService visitors;

    public VisitorController(IpBlacklistService visitors) {
        this.visitors = visitors;
    }

    @GetMapping
    public List<Map<String, Object>> list(CurrentUser user, @RequestParam(defaultValue = "7") int days) {
        return visitors.recentVisitors(user, days);
    }

    @PostMapping("/blacklist")
    public Map<String, Object> block(CurrentUser user, HttpServletRequest request,
                                     @RequestBody IpRequest payload) {
        String currentIp = ClientIpResolver.resolve(request);
        String targetIp = ClientIpResolver.normalize(payload.ipAddress());
        if (currentIp.equals(targetIp)) {
            throw ApiException.badRequest("不能拉黑当前登录 IP");
        }
        visitors.block(user, targetIp);
        return Map.of("ok", true);
    }

    @DeleteMapping("/blacklist")
    public Map<String, Object> unblock(CurrentUser user, @RequestBody IpRequest payload) {
        visitors.unblock(user, payload.ipAddress());
        return Map.of("ok", true);
    }

    public record IpRequest(String ipAddress) {}
}

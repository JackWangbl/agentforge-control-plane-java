package com.agentforge.controlplane.access;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 请求内的身份。tenantId 可能被平台管理员用 X-Tenant-Id 切换过，
 * homeTenantId 始终是用户自己所属的租户。
 */
public class CurrentUser {

    private final Long id;
    private final String username;
    private final String displayName;
    private Long tenantId;
    private final Long homeTenantId;
    private String tenantName;
    private final Long roleId;
    private final String roleName;
    private final List<String> permissions;
    private Instant trialExpiresAt;

    public CurrentUser(Long id, String username, String displayName, Long tenantId, Long homeTenantId,
                       String tenantName, Long roleId, String roleName, List<String> permissions) {
        this.id = id;
        this.username = username;
        this.displayName = displayName;
        this.tenantId = tenantId;
        this.homeTenantId = homeTenantId;
        this.tenantName = tenantName == null ? "" : tenantName;
        this.roleId = roleId;
        this.roleName = roleName == null ? "" : roleName;
        this.permissions = permissions == null ? new ArrayList<>() : new ArrayList<>(permissions);
    }

    public boolean isTrial() {
        return trialExpiresAt != null;
    }

    public Instant getTrialExpiresAt() { return trialExpiresAt; }

    public void setTrialExpiresAt(Instant trialExpiresAt) { this.trialExpiresAt = trialExpiresAt; }

    public boolean isPlatformAdmin() {
        return has("*", "platform:admin");
    }

    /**
     * 只要命中任意一个就算有权限。三条隐含规则跟 Python 版一致：
     * 通配和 platform:admin 直接放行；持有 :write 自动获得对应 :read；eval:run 兼作 eval 读写。
     */
    public boolean has(String... perms) {
        Set<String> granted = new HashSet<>(permissions);
        if (granted.contains("*") || granted.contains("platform:admin")) {
            return true;
        }
        for (String perm : perms) {
            if (granted.contains(perm)) {
                return true;
            }
            if (perm.endsWith(":read") && granted.contains(perm.substring(0, perm.length() - 5) + ":write")) {
                return true;
            }
            if (("eval:read".equals(perm) || "eval:write".equals(perm)) && granted.contains("eval:run")) {
                return true;
            }
        }
        return false;
    }

    public Long getId() { return id; }
    public String getUsername() { return username; }
    public String getDisplayName() { return displayName; }
    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long tenantId) { this.tenantId = tenantId; }
    public Long getHomeTenantId() { return homeTenantId; }
    public String getTenantName() { return tenantName; }
    public void setTenantName(String tenantName) { this.tenantName = tenantName; }
    public Long getRoleId() { return roleId; }
    public String getRoleName() { return roleName; }
    public List<String> getPermissions() { return permissions; }
}

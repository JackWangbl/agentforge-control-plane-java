package com.agentforge.controlplane.web;

import com.agentforge.controlplane.access.AuthService;
import com.agentforge.controlplane.access.CurrentUser;
import com.agentforge.controlplane.access.PasswordHasher;
import com.agentforge.controlplane.access.Permissions;
import com.agentforge.controlplane.access.PublicEndpoint;
import com.agentforge.controlplane.access.RequirePermission;
import com.agentforge.controlplane.domain.Role;
import com.agentforge.controlplane.domain.Tenant;
import com.agentforge.controlplane.domain.User;
import com.agentforge.controlplane.dto.ApiDtos;
import com.agentforge.controlplane.repo.RoleRepository;
import com.agentforge.controlplane.repo.TenantRepository;
import com.agentforge.controlplane.repo.UserRepository;
import com.agentforge.controlplane.util.Jsons;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.ResponseStatus;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
public class AuthController {

    private final AuthService auth;
    private final UserRepository users;
    private final RoleRepository roles;
    private final TenantRepository tenants;

    public AuthController(AuthService auth, UserRepository users, RoleRepository roles, TenantRepository tenants) {
        this.auth = auth;
        this.users = users;
        this.roles = roles;
        this.tenants = tenants;
    }

    public static final String TRIAL_COOKIE = "af_trial";

    @PublicEndpoint
    @PostMapping("/api/auth/trial")
    public Map<String, Object> trial(HttpServletRequest request, HttpServletResponse response) {
        AuthService.TrialGrant grant = auth.beginTrial(trialCookie(request));
        ResponseCookie cookie = ResponseCookie.from(TRIAL_COOKIE, grant.trialKey())
                .httpOnly(true)
                .path("/")
                .maxAge(Duration.ofDays(7))
                .sameSite("Lax")
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
        return Jsons.ordered(
                "token", grant.token(),
                "expires_at", grant.expiresAt(),
                "trial", true);
    }

    @PublicEndpoint
    @PostMapping("/api/auth/login")
    public Map<String, Object> login(@Valid @RequestBody ApiDtos.LoginRequest payload) {
        String token = auth.login(payload.username().strip(), payload.password());
        User user = users.findByUsername(payload.username().strip()).orElseThrow();
        Role role = user.getRoleId() == null ? null : roles.findById(user.getRoleId()).orElse(null);
        Tenant tenant = tenants.findById(user.getTenantId()).orElse(null);
        return Jsons.ordered("token", token, "user", dumpUser(user, role, tenant));
    }

    @PostMapping("/api/auth/logout")
    public Map<String, Object> logout(CurrentUser user, HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        String token = "";
        if (header != null && header.length() > 7 && header.substring(0, 7).equalsIgnoreCase("bearer ")) {
            token = header.substring(7).strip();
        }
        auth.logout(token);
        return Map.of("ok", true);
    }

    @GetMapping("/api/auth/me")
    public Map<String, Object> me(CurrentUser user) {
        List<Map<String, Object>> tenantRows = new ArrayList<>();
        if (user.isPlatformAdmin()) {
            for (Tenant row : tenants.findAll(Sort.by("id"))) {
                tenantRows.add(dumpTenant(row));
            }
        } else {
            tenants.findById(user.getHomeTenantId()).ifPresent(home -> tenantRows.add(dumpTenant(home)));
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", user.getId());
        data.put("username", user.getUsername());
        data.put("display_name", user.getDisplayName());
        data.put("tenant_id", user.getTenantId());
        data.put("home_tenant_id", user.getHomeTenantId());
        data.put("tenant_name", user.getTenantName());
        data.put("role_id", user.getRoleId());
        data.put("role_name", user.getRoleName());
        data.put("permissions", user.getPermissions());
        data.put("is_platform_admin", user.isPlatformAdmin());
        data.put("trial", user.isTrial());
        data.put("trial_expires_at", user.getTrialExpiresAt());
        data.put("tenants", tenantRows);
        data.put("catalog", Permissions.CATALOG);
        return data;
    }

    @RequirePermission({"tenant:admin", "user:read", "role:read"})
    @GetMapping("/api/tenants")
    public List<Map<String, Object>> listTenants(CurrentUser user) {
        List<Tenant> rows = user.isPlatformAdmin()
                ? tenants.findAll(Sort.by("id"))
                : tenants.findById(user.getTenantId()).map(List::of).orElse(List.of());
        return rows.stream().map(row -> Jsons.ordered(
                "id", row.getId(), "slug", row.getSlug(), "name", row.getName(),
                "description", row.getDescription(), "status", row.getStatus())).toList();
    }

    @RequirePermission("platform:admin")
    @ResponseStatus(HttpStatus.CREATED)
    @PostMapping("/api/tenants")
    public Map<String, Object> createTenant(@Valid @RequestBody ApiDtos.TenantCreate payload) {
        String slug = payload.slug().strip().toLowerCase();
        if (tenants.findBySlug(slug).isPresent()) {
            throw ApiException.conflict("租户标识已存在");
        }
        Tenant row = new Tenant();
        row.setSlug(slug);
        row.setName(payload.name().strip());
        row.setDescription(payload.description());
        tenants.save(row);
        return Jsons.ordered("id", row.getId(), "slug", row.getSlug(), "name", row.getName(),
                "description", row.getDescription(), "status", row.getStatus());
    }

    @RequirePermission({"user:read", "role:read", "tenant:admin"})
    @GetMapping("/api/users")
    public List<Map<String, Object>> listUsers(CurrentUser user) {
        List<User> rows = users.findAll(Sort.by(Sort.Direction.DESC, "id"));
        if (!user.isPlatformAdmin()) {
            rows = rows.stream().filter(row -> user.getTenantId().equals(row.getTenantId())).toList();
        }
        Map<Long, Role> roleIndex = new LinkedHashMap<>();
        roles.findAll().forEach(row -> roleIndex.put(row.getId(), row));
        Map<Long, Tenant> tenantIndex = new LinkedHashMap<>();
        tenants.findAll().forEach(row -> tenantIndex.put(row.getId(), row));
        return rows.stream()
                .map(row -> dumpUser(row, roleIndex.get(row.getRoleId()), tenantIndex.get(row.getTenantId())))
                .toList();
    }

    @RequirePermission({"user:write", "tenant:admin"})
    @ResponseStatus(HttpStatus.CREATED)
    @PostMapping("/api/users")
    public Map<String, Object> createUser(CurrentUser actor, @Valid @RequestBody ApiDtos.UserCreate payload) {
        if (users.findByUsername(payload.username().strip()).isPresent()) {
            throw ApiException.conflict("用户名已存在");
        }
        Long tenantId = payload.tenant_id() == null ? actor.getTenantId() : payload.tenant_id();
        if (!actor.isPlatformAdmin()) {
            tenantId = actor.getTenantId();
        }
        Role role = roles.findById(payload.role_id()).orElse(null);
        if (role == null || (role.getTenantId() != null && !role.getTenantId().equals(tenantId) && !actor.isPlatformAdmin())) {
            throw ApiException.badRequest("角色不存在或不属于当前租户");
        }
        User row = new User();
        row.setTenantId(tenantId);
        row.setUsername(payload.username().strip());
        row.setDisplayName(payload.display_name().isBlank() ? payload.username() : payload.display_name());
        row.setPasswordHash(PasswordHasher.hash(payload.password()));
        row.setRoleId(payload.role_id());
        row.setEnabled(payload.enabled());
        users.save(row);
        if (role != null) {
            role.setUserCount(role.getUserCount() + 1);
            roles.save(role);
        }
        return dumpUser(row, role, tenants.findById(tenantId).orElse(null));
    }

    @RequirePermission({"user:write", "tenant:admin"})
    @PutMapping("/api/users/{userId}")
    public Map<String, Object> updateUser(CurrentUser actor, @PathVariable Long userId,
                                          @RequestBody ApiDtos.UserUpdate payload) {
        User row = users.findById(userId).orElse(null);
        if (row == null || (!actor.getTenantId().equals(row.getTenantId()) && !actor.isPlatformAdmin())) {
            throw ApiException.notFound("用户不存在");
        }
        if (payload.password() != null && !payload.password().isBlank()) {
            row.setPasswordHash(PasswordHasher.hash(payload.password()));
        }
        if (payload.display_name() != null) {
            row.setDisplayName(payload.display_name());
        }
        if (payload.role_id() != null) {
            row.setRoleId(payload.role_id());
        }
        if (payload.enabled() != null) {
            row.setEnabled(payload.enabled());
        }
        if (payload.tenant_id() != null && actor.isPlatformAdmin()) {
            row.setTenantId(payload.tenant_id());
        }
        users.save(row);
        Role role = row.getRoleId() == null ? null : roles.findById(row.getRoleId()).orElse(null);
        if (role != null) {
            role.setUserCount(users.countByRoleId(role.getId()));
            roles.save(role);
        }
        return dumpUser(row, role, tenants.findById(row.getTenantId()).orElse(null));
    }

    private static Map<String, Object> dumpUser(User row, Role role, Tenant tenant) {
        return Jsons.ordered(
                "id", row.getId(),
                "username", row.getUsername(),
                "display_name", row.getDisplayName(),
                "tenant_id", row.getTenantId(),
                "tenant_name", tenant == null ? "" : tenant.getName(),
                "role_id", row.getRoleId(),
                "role_name", role == null ? "" : role.getName(),
                "permissions", role == null || role.getPermissions() == null ? List.of() : role.getPermissions(),
                "enabled", row.isEnabled(),
                "created_at", Jsons.iso(row.getCreatedAt()));
    }

    private static Map<String, Object> dumpTenant(Tenant row) {
        return Jsons.ordered("id", row.getId(), "slug", row.getSlug(), "name", row.getName(),
                "description", row.getDescription());
    }

    private static String trialCookie(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return "";
        }
        for (Cookie cookie : cookies) {
            if (TRIAL_COOKIE.equals(cookie.getName())) {
                return cookie.getValue() == null ? "" : cookie.getValue().strip();
            }
        }
        return "";
    }
}

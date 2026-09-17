package com.agentforge.controlplane.access;

import com.agentforge.controlplane.config.AppSettings;
import com.agentforge.controlplane.domain.AuthToken;
import com.agentforge.controlplane.domain.Role;
import com.agentforge.controlplane.domain.Tenant;
import com.agentforge.controlplane.domain.User;
import com.agentforge.controlplane.repo.AuthTokenRepository;
import com.agentforge.controlplane.repo.RoleRepository;
import com.agentforge.controlplane.repo.TenantRepository;
import com.agentforge.controlplane.repo.UserRepository;
import com.agentforge.controlplane.web.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

@Service
public class AuthService {

    /** 与 Python 版 TOKEN_TTL_HOURS = 24 * 7 一致。 */
    private static final Duration TOKEN_TTL = Duration.ofHours(24 * 7);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final UserRepository users;
    private final RoleRepository roles;
    private final TenantRepository tenants;
    private final AuthTokenRepository tokens;
    private final AppSettings settings;

    public AuthService(UserRepository users, RoleRepository roles, TenantRepository tenants,
                       AuthTokenRepository tokens, AppSettings settings) {
        this.users = users;
        this.roles = roles;
        this.tenants = tenants;
        this.tokens = tokens;
        this.settings = settings;
    }

    /** secrets.token_urlsafe(32) 的等价物。 */
    public static String newToken() {
        byte[] raw = new byte[32];
        RANDOM.nextBytes(raw);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    }

    @Transactional
    public String login(String username, String password) {
        User user = users.findByUsername(username)
                .filter(User::isEnabled)
                .filter(row -> PasswordHasher.verify(password, row.getPasswordHash()))
                .orElseThrow(() -> ApiException.unauthorized("用户名或密码错误"));
        AuthToken token = new AuthToken();
        token.setToken(newToken());
        token.setUserId(user.getId());
        token.setExpiresAt(Instant.now().plus(TOKEN_TTL));
        tokens.save(token);
        return token.getToken();
    }

    @Transactional
    public void logout(String token) {
        if (token != null && !token.isBlank()) {
            tokens.deleteByToken(token);
        }
    }

    @Transactional(readOnly = true)
    public CurrentUser resolve(String authorizationHeader, String tenantHeader) {
        Long tenantOverride = parseTenant(tenantHeader);
        String token = bearerToken(authorizationHeader);
        if (!token.isBlank()) {
            AuthToken row = tokens.findByToken(token)
                    .orElseThrow(() -> ApiException.unauthorized("登录已过期，请重新登录"));
            if (row.getExpiresAt() == null || row.getExpiresAt().isBefore(Instant.now())) {
                throw ApiException.unauthorized("登录已过期，请重新登录");
            }
            return loadUser(row.getUserId(), tenantOverride);
        }
        String devUser = settings.getAuthDevUser().strip();
        if (!devUser.isBlank()) {
            Optional<User> impersonated = users.findByUsername(devUser);
            if (impersonated.isPresent()) {
                return loadUser(impersonated.get().getId(), tenantOverride);
            }
        }
        throw ApiException.unauthorized("请先登录");
    }

    @Transactional(readOnly = true)
    public CurrentUser loadUser(Long userId, Long tenantOverride) {
        User user = users.findById(userId)
                .filter(User::isEnabled)
                .orElseThrow(() -> ApiException.unauthorized("登录已失效"));
        Role role = user.getRoleId() == null ? null : roles.findById(user.getRoleId()).orElse(null);
        Tenant home = user.getTenantId() == null ? null : tenants.findById(user.getTenantId()).orElse(null);
        List<String> permissions = role == null ? List.of() : role.getPermissions();
        CurrentUser actor = new CurrentUser(
                user.getId(),
                user.getUsername(),
                user.getDisplayName() == null || user.getDisplayName().isBlank()
                        ? user.getUsername() : user.getDisplayName(),
                user.getTenantId(),
                user.getTenantId(),
                home == null ? "" : home.getName(),
                user.getRoleId(),
                role == null ? "" : role.getName(),
                permissions);
        if (tenantOverride != null && !tenantOverride.equals(user.getTenantId())) {
            if (!actor.isPlatformAdmin()) {
                throw ApiException.forbidden("不能切换到其他租户");
            }
            Tenant target = tenants.findById(tenantOverride)
                    .orElseThrow(() -> ApiException.notFound("租户不存在"));
            actor.setTenantId(target.getId());
            actor.setTenantName(target.getName());
        }
        return actor;
    }

    private static String bearerToken(String header) {
        if (header == null) {
            return "";
        }
        String value = header.strip();
        if (value.length() > 7 && value.substring(0, 7).equalsIgnoreCase("bearer ")) {
            return value.substring(7).strip();
        }
        return "";
    }

    private static Long parseTenant(String header) {
        if (header == null || header.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(header.strip());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}

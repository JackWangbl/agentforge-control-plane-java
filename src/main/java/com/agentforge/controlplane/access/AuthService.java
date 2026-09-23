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
    /** 未登录可直接进页面的时长。同一浏览器记一次，到点后必须登录。 */
    public static final Duration TRIAL_TTL = Duration.ofMinutes(5);
    public static final String TRIAL_EXPIRED = "试用已结束，请登录";
    public static final String TRIAL_USER = "guest";
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

    /**
     * 这个浏览器还没有试用记录时发 5 分钟凭证；还在有效期内则沿用原到期时间；
     * 已经用完则拒绝，避免清掉本地 token 再刷一次就重新计时。
     */
    @Transactional
    public TrialGrant beginTrial(String trialKey) {
        boolean known = trialKey != null && !trialKey.isBlank();
        AuthToken existing = known ? tokens.findByTrialKey(trialKey).orElse(null) : null;
        String decision = trialDecision(Instant.now(), existing != null, existing == null ? null : existing.getExpiresAt());
        if ("resume".equals(decision)) {
            return new TrialGrant(existing.getToken(), existing.getExpiresAt(), existing.getTrialKey());
        }
        if ("expired".equals(decision)) {
            throw ApiException.unauthorized(TRIAL_EXPIRED);
        }
        User guest = users.findByUsername(TRIAL_USER)
                .filter(User::isEnabled)
                .orElseThrow(() -> ApiException.unauthorized("试用暂不可用"));
        AuthToken token = new AuthToken();
        token.setToken(newToken());
        token.setUserId(guest.getId());
        token.setTrialKey(newToken());
        token.setExpiresAt(Instant.now().plus(TRIAL_TTL));
        tokens.save(token);
        return new TrialGrant(token.getToken(), token.getExpiresAt(), token.getTrialKey());
    }

    /** known 为 false 表示这个浏览器还没有试用记录。 */
    static String trialDecision(Instant now, boolean known, Instant expiresAt) {
        if (!known) {
            return "new";
        }
        if (expiresAt != null && expiresAt.isAfter(now)) {
            return "resume";
        }
        return "expired";
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
            if (row.getExpiresAt() == null || !row.getExpiresAt().isAfter(Instant.now())) {
                if (row.getTrialKey() != null && !row.getTrialKey().isBlank()) {
                    throw ApiException.unauthorized(TRIAL_EXPIRED);
                }
                throw ApiException.unauthorized("登录已过期，请重新登录");
            }
            CurrentUser actor = loadUser(row.getUserId(), tenantOverride);
            if (row.getTrialKey() != null && !row.getTrialKey().isBlank()) {
                actor.setTrialExpiresAt(row.getExpiresAt());
            }
            return actor;
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

    public record TrialGrant(String token, Instant expiresAt, String trialKey) {}

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

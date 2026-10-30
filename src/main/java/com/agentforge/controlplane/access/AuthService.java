package com.agentforge.controlplane.access;

import com.agentforge.controlplane.config.AppSettings;
import com.agentforge.controlplane.domain.AuthToken;
import com.agentforge.controlplane.domain.Role;
import com.agentforge.controlplane.domain.Tenant;
import com.agentforge.controlplane.domain.User;
import com.agentforge.controlplane.memory.MemoryService;
import com.agentforge.controlplane.repo.AuthTokenRepository;
import com.agentforge.controlplane.repo.ConversationRepository;
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
    /**
     * auth_tokens.expires_at 不能为空。试用不按时间失效，这只是占位。
     * 是否还能继续用，只看累计 Token 是否达到 {@link #TRIAL_TOKEN_LIMIT}。
     */
    private static final Duration TRIAL_RECORD_TTL = Duration.ofDays(3650);
    /** 同一试用凭证累计消耗达到该值后必须登录。 */
    public static final int TRIAL_TOKEN_LIMIT = 10_000;
    public static final String TRIAL_TOKEN_EXHAUSTED = "试用 Token 已用完，请登录";
    public static final String TRIAL_USER = "guest";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final UserRepository users;
    private final RoleRepository roles;
    private final TenantRepository tenants;
    private final AuthTokenRepository tokens;
    private final ConversationRepository conversations;
    private final AppSettings settings;

    public AuthService(UserRepository users, RoleRepository roles, TenantRepository tenants,
                       AuthTokenRepository tokens, ConversationRepository conversations, AppSettings settings) {
        this.users = users;
        this.roles = roles;
        this.tenants = tenants;
        this.tokens = tokens;
        this.conversations = conversations;
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
     * 这个浏览器还没有试用记录时发凭证；已经领过则沿用原来的凭证。
     * 不按时间结束。累计 Token 用尽后拒绝，避免清掉本地 token 再领一份额度。
     */
    @Transactional
    public TrialGrant beginTrial(String trialKey) {
        boolean known = trialKey != null && !trialKey.isBlank();
        AuthToken existing = known ? tokens.findByTrialKey(trialKey).orElse(null) : null;
        if ("resume".equals(trialDecision(existing != null))) {
            rejectIfTrialTokensExhausted(existing.getTrialKey());
            return new TrialGrant(existing.getToken(), existing.getExpiresAt(), existing.getTrialKey());
        }
        User guest = users.findByUsername(TRIAL_USER)
                .filter(User::isEnabled)
                .orElseThrow(() -> ApiException.unauthorized("试用暂不可用"));
        AuthToken token = new AuthToken();
        token.setToken(newToken());
        token.setUserId(guest.getId());
        token.setTrialKey(newToken());
        token.setExpiresAt(Instant.now().plus(TRIAL_RECORD_TTL));
        tokens.save(token);
        return new TrialGrant(token.getToken(), token.getExpiresAt(), token.getTrialKey());
    }

    /** known 为 false 表示这个浏览器还没有试用记录。试用不因时间结束。 */
    static String trialDecision(boolean known) {
        return known ? "resume" : "new";
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
            boolean trial = row.getTrialKey() != null && !row.getTrialKey().isBlank();
            if (!trial && (row.getExpiresAt() == null || !row.getExpiresAt().isAfter(Instant.now()))) {
                throw ApiException.unauthorized("登录已过期，请重新登录");
            }
            CurrentUser actor = loadUser(row.getUserId(), tenantOverride);
            if (trial) {
                long used = trialTokensUsed(row.getTrialKey());
                if (trialTokensExhausted(used)) {
                    throw ApiException.unauthorized(TRIAL_TOKEN_EXHAUSTED);
                }
                actor.setTrialExpiresAt(row.getExpiresAt());
                actor.setTrialKey(row.getTrialKey().strip());
                actor.setTrialTokensUsed(used);
                actor.setTrialTokenLimit(TRIAL_TOKEN_LIMIT);
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

    static boolean trialTokensExhausted(long used) {
        return used >= TRIAL_TOKEN_LIMIT;
    }

    private void rejectIfTrialTokensExhausted(String trialKey) {
        if (trialTokensExhausted(trialTokensUsed(trialKey))) {
            throw ApiException.unauthorized(TRIAL_TOKEN_EXHAUSTED);
        }
    }

    private long trialTokensUsed(String trialKey) {
        String subject = MemoryService.trialSubjectKey(trialKey);
        if (subject == null) {
            return 0L;
        }
        Long used = conversations.sumTotalTokensBySubjectKey(subject);
        return used == null ? 0L : used;
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

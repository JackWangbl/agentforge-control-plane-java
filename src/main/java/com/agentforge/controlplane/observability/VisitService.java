package com.agentforge.controlplane.observability;

import com.agentforge.controlplane.access.CurrentUser;
import com.agentforge.controlplane.repo.VisitorDayRepository;
import com.agentforge.controlplane.web.AuthController;
import jakarta.persistence.EntityManager;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.ZoneId;

/** 按北京时间统计打开概览的次数。同一个人反复进入也会累加。 */
@Service
public class VisitService {

    static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private final EntityManager em;
    private final VisitorDayRepository visits;

    public VisitService(EntityManager em, VisitorDayRepository visits) {
        this.em = em;
        this.visits = visits;
    }

    @Transactional
    public VisitCounts recordAndCount(CurrentUser user, HttpServletRequest request) {
        LocalDate today = LocalDate.now(ZONE);
        String key = visitorKey(user, request);
        String ipAddress = ClientIpResolver.resolve(request);
        em.createNativeQuery("""
                        INSERT INTO visitor_days (tenant_id, visitor_key, visited_on, ip_address, created_at)
                        VALUES (:tenantId, :key, :day, :ip, UTC_TIMESTAMP(6))
                        """)
                .setParameter("tenantId", user.getTenantId())
                .setParameter("key", key)
                .setParameter("day", java.sql.Date.valueOf(today))
                .setParameter("ip", ipAddress)
                .executeUpdate();
        return new VisitCounts(visits.count(), visits.countByVisitedOn(today));
    }

    static String visitorKey(CurrentUser user, HttpServletRequest request) {
        if (user.isTrial()) {
            String trial = trialCookie(request);
            if (!trial.isBlank()) {
                return clip("trial:" + trial);
            }
        }
        return "user:" + user.getId();
    }

    private static String trialCookie(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return "";
        }
        for (Cookie cookie : cookies) {
            if (AuthController.TRIAL_COOKIE.equals(cookie.getName()) && cookie.getValue() != null) {
                return cookie.getValue().strip();
            }
        }
        return "";
    }

    private static String clip(String value) {
        return value.length() <= 120 ? value : value.substring(0, 120);
    }

    public record VisitCounts(long total, long today) {}
}

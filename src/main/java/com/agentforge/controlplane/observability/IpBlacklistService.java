package com.agentforge.controlplane.observability;

import com.agentforge.controlplane.access.CurrentUser;
import com.agentforge.controlplane.web.ApiException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 近期开屏访问 IP 的统计及租户级黑名单管理。 */
@Service
public class IpBlacklistService {

    private final JdbcTemplate jdbc;

    public IpBlacklistService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean isBlocked(Long tenantId, String ipAddress) {
        if (tenantId == null || ipAddress == null || ipAddress.isBlank()) {
            return false;
        }
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ip_blacklists WHERE tenant_id = ? AND ip_address = ?",
                Integer.class, tenantId, ipAddress);
        return count != null && count > 0;
    }

    public List<Map<String, Object>> recentVisitors(CurrentUser user, int days) {
        int safeDays = Math.max(1, Math.min(days, 7));
        LocalDate since = LocalDate.now(VisitService.ZONE).minusDays(safeDays - 1L);
        Timestamp sinceAt = Timestamp.from(since.atStartOfDay(VisitService.ZONE).toInstant());
        return jdbc.query("""
                        SELECT v.ip_address, COUNT(*) AS visit_count,
                               MIN(v.created_at) AS first_visited_at,
                               MAX(v.created_at) AS last_visited_at,
                               MAX(CASE WHEN b.id IS NULL THEN 0 ELSE 1 END) AS blocked,
                               COALESCE(tok.total_tokens, 0) AS total_tokens
                        FROM visitor_days v
                        LEFT JOIN ip_blacklists b
                          ON b.tenant_id = ? AND b.ip_address = v.ip_address
                        LEFT JOIN (
                          SELECT c.ip_address, SUM(c.total_tokens) AS total_tokens
                          FROM conversations c
                          WHERE c.tenant_id = ? AND c.ip_address IS NOT NULL AND c.ip_address <> ''
                            AND c.updated_at >= ?
                          GROUP BY c.ip_address
                        ) tok ON tok.ip_address = v.ip_address
                        WHERE v.tenant_id = ? AND v.visited_on >= ?
                          AND v.ip_address IS NOT NULL AND v.ip_address <> ''
                        GROUP BY v.ip_address, tok.total_tokens
                        ORDER BY visit_count DESC, last_visited_at DESC
                        """,
                (rs, rowNum) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("ip_address", rs.getString("ip_address"));
                    row.put("visit_count", rs.getLong("visit_count"));
                    row.put("total_tokens", rs.getLong("total_tokens"));
                    row.put("first_visited_at", instant(rs.getTimestamp("first_visited_at")));
                    row.put("last_visited_at", instant(rs.getTimestamp("last_visited_at")));
                    row.put("blocked", rs.getInt("blocked") > 0);
                    return row;
                }, user.getTenantId(), user.getTenantId(), sinceAt, user.getTenantId(), java.sql.Date.valueOf(since));
    }

    @Transactional
    public void block(CurrentUser user, String rawIp) {
        String ip = requireIp(rawIp);
        jdbc.update("""
                        INSERT INTO ip_blacklists (tenant_id, ip_address, created_by, created_at)
                        VALUES (?, ?, ?, UTC_TIMESTAMP(6))
                        ON DUPLICATE KEY UPDATE created_by = VALUES(created_by)
                        """, user.getTenantId(), ip, user.getId());
    }

    @Transactional
    public void unblock(CurrentUser user, String rawIp) {
        jdbc.update("DELETE FROM ip_blacklists WHERE tenant_id = ? AND ip_address = ?",
                user.getTenantId(), requireIp(rawIp));
    }

    private static String requireIp(String rawIp) {
        String ip = ClientIpResolver.normalize(rawIp);
        if (ip.isBlank()) {
            throw ApiException.badRequest("IP 地址格式不正确");
        }
        return ip;
    }

    private static Object instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}

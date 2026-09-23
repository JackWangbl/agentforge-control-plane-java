package com.agentforge.controlplane.rag;

import com.agentforge.controlplane.access.CurrentUser;
import com.agentforge.controlplane.domain.KnowledgeBase;
import com.agentforge.controlplane.domain.KnowledgeMember;
import com.agentforge.controlplane.domain.User;
import com.agentforge.controlplane.repo.KnowledgeBaseRepository;
import com.agentforge.controlplane.repo.KnowledgeMemberRepository;
import com.agentforge.controlplane.repo.UserRepository;
import com.agentforge.controlplane.repo.VectorStoreRepository;
import com.agentforge.controlplane.domain.VectorStore;
import com.agentforge.controlplane.web.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class KnowledgeAccess {

    private final KnowledgeBaseRepository bases;
    private final KnowledgeMemberRepository members;
    private final UserRepository users;
    private final VectorStoreRepository vectorStores;

    public KnowledgeAccess(KnowledgeBaseRepository bases, KnowledgeMemberRepository members,
                           UserRepository users, VectorStoreRepository vectorStores) {
        this.bases = bases;
        this.members = members;
        this.users = users;
        this.vectorStores = vectorStores;
    }

    public List<KnowledgeBase> listForConsole(CurrentUser user) {
        if (user == null || !user.has("knowledge:read") && !user.has("tenant:admin")) {
            return List.of();
        }
        List<KnowledgeBase> rows = bases.findByTenantIdOrderByIdDesc(user.getTenantId());
        List<KnowledgeBase> visible = new ArrayList<>();
        for (KnowledgeBase row : rows) {
            if (canViewInConsole(user, row)) {
                visible.add(row);
            }
        }
        return visible;
    }

    public KnowledgeBase requireConsole(CurrentUser user, Long id) {
        KnowledgeBase row = bases.findById(id).orElseThrow(() -> ApiException.notFound("知识库不存在"));
        if (user == null || !user.getTenantId().equals(row.getTenantId()) || !canViewInConsole(user, row)) {
            throw ApiException.notFound("知识库不存在");
        }
        return row;
    }

    public KnowledgeBase requireDocuments(CurrentUser user, Long id) {
        KnowledgeBase row = requireConsole(user, id);
        if (!canEditDocuments(user, row)) {
            throw ApiException.forbidden("只有所有者和编辑者可以修改文档");
        }
        return row;
    }

    public KnowledgeBase requireManage(CurrentUser user, Long id) {
        KnowledgeBase row = requireConsole(user, id);
        if (!canManage(user, row)) {
            throw ApiException.forbidden("只有知识库所有者可以修改设置");
        }
        return row;
    }

    public boolean canRetrieve(CurrentUser user, KnowledgeBase row) {
        if (user == null || row == null || !user.getTenantId().equals(row.getTenantId())) {
            return false;
        }
        return KnowledgeAcl.canRetrieve(isMember(user, row), tenantVisible(row), user.has("knowledge:read"));
    }

    public boolean canViewInConsole(CurrentUser user, KnowledgeBase row) {
        if (user == null || row == null || !user.getTenantId().equals(row.getTenantId())) {
            return false;
        }
        return KnowledgeAcl.canViewInConsole(isMember(user, row), tenantVisible(row),
                user.has("knowledge:read"), user.has("tenant:admin"));
    }

    public boolean canEditDocuments(CurrentUser user, KnowledgeBase row) {
        return KnowledgeAcl.canEditDocuments(roleOf(user, row), user != null && user.has("knowledge:write"));
    }

    public boolean canManage(CurrentUser user, KnowledgeBase row) {
        return KnowledgeAcl.canManage(roleOf(user, row), user != null && user.has("knowledge:write"));
    }

    public String roleOf(CurrentUser user, KnowledgeBase row) {
        if (user == null || row == null || row.getId() == null) {
            return "";
        }
        return members.findByKnowledgeIdAndUserId(row.getId(), user.getId())
                .map(KnowledgeMember::getRole)
                .orElse("");
    }

    public String relation(CurrentUser user, KnowledgeBase row) {
        String role = roleOf(user, row);
        if ("owner".equals(role)) {
            return "owned";
        }
        if (!role.isBlank()) {
            return "shared";
        }
        if (tenantVisible(row)) {
            return "tenant";
        }
        return "admin";
    }

    @Transactional
    public void addOwner(KnowledgeBase row, CurrentUser user) {
        KnowledgeMember member = new KnowledgeMember();
        member.setKnowledgeId(row.getId());
        member.setUserId(user.getId());
        member.setRole("owner");
        members.save(member);
    }

    public List<Long> retainVisible(CurrentUser user, List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        List<Long> kept = new ArrayList<>();
        for (Long id : ids) {
            KnowledgeBase row = bases.findById(id).orElse(null);
            if (row != null && canViewInConsole(user, row)) {
                kept.add(id);
            }
        }
        return kept;
    }

    public void assertCanBind(CurrentUser user, List<Long> ids, List<Long> previousIds) {
        if (ids == null || ids.isEmpty()) {
            return;
        }
        List<Long> previous = previousIds == null ? List.of() : previousIds;
        for (Long id : ids) {
            if (previous.contains(id)) {
                KnowledgeBase row = bases.findById(id).orElse(null);
                if (row == null || !user.getTenantId().equals(row.getTenantId())) {
                    throw ApiException.unprocessable("知识库不存在或不属于当前租户");
                }
                continue;
            }
            KnowledgeBase row = bases.findById(id).orElse(null);
            if (row == null || !canViewInConsole(user, row)) {
                throw ApiException.unprocessable("知识库不存在或不属于当前租户");
            }
        }
    }

    public List<Map<String, Object>> listMembers(CurrentUser user, KnowledgeBase row) {
        requireConsole(user, row.getId());
        List<Map<String, Object>> body = new ArrayList<>();
        for (KnowledgeMember member : members.findByKnowledgeIdOrderByIdAsc(row.getId())) {
            body.add(dumpMember(member));
        }
        return body;
    }

    public List<Map<String, Object>> shareCandidates(CurrentUser user) {
        if (user == null || !user.has("knowledge:write")) {
            return List.of();
        }
        List<Map<String, Object>> body = new ArrayList<>();
        for (User person : users.findByTenantIdOrderByIdAsc(user.getTenantId())) {
            if (!person.isEnabled()) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", person.getId());
            item.put("username", person.getUsername());
            item.put("display_name", person.getDisplayName());
            body.add(item);
        }
        return body;
    }

    @Transactional
    public Map<String, Object> grant(CurrentUser actor, KnowledgeBase row, Long userId, String role) {
        if (!canManage(actor, row)) {
            throw ApiException.forbidden("只有知识库所有者可以修改成员");
        }
        String normalized = normalizeRole(role);
        User person = users.findById(userId == null ? 0L : userId)
                .orElseThrow(() -> ApiException.unprocessable("用户不存在"));
        if (!actor.getTenantId().equals(person.getTenantId()) || !person.isEnabled()) {
            throw ApiException.unprocessable("只能分享给本租户已启用的用户");
        }
        KnowledgeMember member = members.findByKnowledgeIdAndUserId(row.getId(), person.getId()).orElse(null);
        if (member != null && "owner".equals(member.getRole()) && !"owner".equals(normalized)
                && members.countByKnowledgeIdAndRole(row.getId(), "owner") <= 1) {
            throw ApiException.unprocessable("至少保留一位所有者");
        }
        if (member == null) {
            member = new KnowledgeMember();
            member.setKnowledgeId(row.getId());
            member.setUserId(person.getId());
        }
        member.setRole(normalized);
        return dumpMember(members.save(member));
    }

    @Transactional
    public void revoke(CurrentUser actor, KnowledgeBase row, Long userId) {
        if (!canManage(actor, row)) {
            throw ApiException.forbidden("只有知识库所有者可以修改成员");
        }
        KnowledgeMember member = members.findByKnowledgeIdAndUserId(row.getId(), userId).orElse(null);
        if (member == null) {
            return;
        }
        if ("owner".equals(member.getRole()) && members.countByKnowledgeIdAndRole(row.getId(), "owner") <= 1) {
            throw ApiException.unprocessable("至少保留一位所有者");
        }
        members.delete(member);
    }

    @Transactional
    public void deleteMembers(Long knowledgeId) {
        members.deleteByKnowledgeId(knowledgeId);
    }

    public VectorStore defaultVectorStore(Long tenantId) {
        VectorStore fallback = null;
        for (VectorStore row : vectorStores.findByTenantIdOrderByIdDesc(tenantId)) {
            if (!row.isEnabled()) {
                continue;
            }
            if (row.isDefault()) {
                return row;
            }
            fallback = row;
        }
        if (fallback != null) {
            return fallback;
        }
        throw ApiException.unprocessable("请管理员先在平台设置里登记 Milvus");
    }

    private Map<String, Object> dumpMember(KnowledgeMember member) {
        User person = users.findById(member.getUserId()).orElse(null);
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("user_id", member.getUserId());
        item.put("username", person == null ? "" : person.getUsername());
        item.put("display_name", person == null ? "" : person.getDisplayName());
        item.put("role", member.getRole());
        return item;
    }

    private boolean isMember(CurrentUser user, KnowledgeBase row) {
        return !roleOf(user, row).isBlank();
    }

    private static boolean tenantVisible(KnowledgeBase row) {
        return "tenant".equals(row.getVisibility());
    }

    public static String normalizeRole(String role) {
        String value = role == null ? "" : role.strip();
        if (!"owner".equals(value) && !"editor".equals(value) && !"viewer".equals(value)) {
            throw ApiException.unprocessable("成员角色只能是 owner、editor 或 viewer");
        }
        return value;
    }

    public static String normalizeVisibility(String visibility) {
        String value = visibility == null || visibility.isBlank() ? "private" : visibility.strip();
        if (!"private".equals(value) && !"tenant".equals(value)) {
            throw ApiException.unprocessable("可见性只能是 private 或 tenant");
        }
        return value;
    }
}

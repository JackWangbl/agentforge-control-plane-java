package com.agentforge.controlplane.access;

import com.agentforge.controlplane.domain.Agent;
import com.agentforge.controlplane.domain.Conversation;
import com.agentforge.controlplane.domain.Dataset;
import com.agentforge.controlplane.domain.EvaluationRun;
import com.agentforge.controlplane.domain.Experiment;
import com.agentforge.controlplane.domain.HttpAgent;
import com.agentforge.controlplane.domain.McpServer;
import com.agentforge.controlplane.domain.ModelConfig;
import com.agentforge.controlplane.domain.OpenCliEndpoint;
import com.agentforge.controlplane.domain.Role;
import com.agentforge.controlplane.domain.SandboxPolicy;
import com.agentforge.controlplane.domain.Skill;
import com.agentforge.controlplane.domain.Trace;
import com.agentforge.controlplane.domain.User;
import com.agentforge.controlplane.domain.Workflow;
import com.agentforge.controlplane.web.ApiException;
import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Root;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 对齐 Python 版 app/access/service.py。可见性是租户内共享：
 * 同租户成员只要有对应读权限就能看到彼此的资源，有写权限就能改；跨租户一律拒绝。
 */
@Service
public class ResourceAccessService {

    private static final Map<ResourceKind, Class<?>> KIND_MODELS = Map.ofEntries(
            Map.entry(ResourceKind.AGENT, Agent.class),
            Map.entry(ResourceKind.HTTP_AGENT, HttpAgent.class),
            Map.entry(ResourceKind.CREDENTIAL, ModelConfig.class),
            Map.entry(ResourceKind.MCP, McpServer.class),
            Map.entry(ResourceKind.OPENCLI, OpenCliEndpoint.class),
            Map.entry(ResourceKind.SKILL, Skill.class),
            Map.entry(ResourceKind.WORKFLOW, Workflow.class),
            Map.entry(ResourceKind.SANDBOX, SandboxPolicy.class),
            Map.entry(ResourceKind.DATASET, Dataset.class),
            Map.entry(ResourceKind.EVALUATION, EvaluationRun.class),
            Map.entry(ResourceKind.EXPERIMENT, Experiment.class),
            Map.entry(ResourceKind.SESSION, Conversation.class),
            Map.entry(ResourceKind.TRACE, Trace.class),
            Map.entry(ResourceKind.ROLE, Role.class),
            Map.entry(ResourceKind.USER, User.class));

    private final EntityManager em;

    public ResourceAccessService(EntityManager em) {
        this.em = em;
    }

    public static Class<?> modelOf(ResourceKind kind) {
        Class<?> model = KIND_MODELS.get(kind);
        if (model == null) {
            throw new IllegalArgumentException("没有登记该资源种类：" + kind);
        }
        return model;
    }

    @Transactional(readOnly = true)
    @SuppressWarnings("unchecked")
    public <T> List<T> listRows(CurrentUser user, ResourceKind kind) {
        if (!user.has(Permissions.READ.get(kind))) {
            throw ApiException.forbidden("没有权限查看该资源");
        }
        Class<T> model = (Class<T>) modelOf(kind);
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<T> query = cb.createQuery(model);
        Root<T> root = query.from(model);
        if (hasField(model, "tenantId")) {
            query.where(cb.or(
                    cb.equal(root.get("tenantId"), user.getTenantId()),
                    cb.isNull(root.get("tenantId"))));
        }
        query.orderBy(cb.desc(root.get("id")));
        List<T> rows = em.createQuery(query).getResultList();

        // 自己的（含租户公共资源）永远可见，其余同租户资源由共享策略放行
        List<T> visible = new ArrayList<>();
        for (T row : rows) {
            Long ownerId = ownerOf(row);
            if (ownerId == null || ownerId.equals(user.getId()) || sharedWithin(user, kind)) {
                visible.add(row);
            }
        }
        return visible;
    }

    @Transactional(readOnly = true)
    @SuppressWarnings("unchecked")
    public <T> T getRow(CurrentUser user, ResourceKind kind, Long resourceId) {
        if (!user.has(Permissions.READ.get(kind))) {
            throw ApiException.notFound("资源不存在");
        }
        Class<T> model = (Class<T>) modelOf(kind);
        T row = em.find(model, resourceId);
        if (row == null) {
            throw ApiException.notFound("资源不存在");
        }
        Long tenantId = tenantOf(row);
        if (tenantId != null && !tenantId.equals(user.getTenantId())) {
            throw ApiException.notFound("资源不存在");
        }
        Long ownerId = ownerOf(row);
        if (ownerId == null || ownerId.equals(user.getId()) || sharedWithin(user, kind)) {
            return row;
        }
        throw ApiException.notFound("资源不存在");
    }

    @Transactional(readOnly = true)
    public <T> T resolveForEdit(CurrentUser user, ResourceKind kind, Long resourceId) {
        T row = getRow(user, kind, resourceId);
        if (!canEdit(user, kind, row)) {
            throw ApiException.forbidden("该资源对当前用户只读");
        }
        return row;
    }

    public boolean canEdit(CurrentUser user, ResourceKind kind, Object row) {
        if (!user.has(Permissions.WRITE.get(kind))) {
            return false;
        }
        Long ownerId = ownerOf(row);
        return ownerId == null || ownerId.equals(user.getId()) || sharedWithin(user, kind);
    }

    /** 密钥等敏感字段只对所有者和租户管理员展示。 */
    public boolean canViewSecret(CurrentUser user, Object row) {
        Long ownerId = ownerOf(row);
        return ownerId == null || ownerId.equals(user.getId()) || user.has("tenant:admin", "*");
    }

    /** 新建资源时打上租户和所有者。 */
    public void stampOwner(Object row, CurrentUser user) {
        invokeSetter(row, "setTenantId", user.getTenantId());
        invokeSetter(row, "setOwnerId", user.getId());
    }

    private boolean sharedWithin(CurrentUser user, ResourceKind kind) {
        return user.has(Permissions.READ.get(kind));
    }

    private static Long tenantOf(Object row) {
        return (Long) invokeGetter(row, "getTenantId");
    }

    private static Long ownerOf(Object row) {
        return (Long) invokeGetter(row, "getOwnerId");
    }

    private static Object invokeGetter(Object row, String name) {
        try {
            Method method = row.getClass().getMethod(name);
            return method.invoke(row);
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    private static void invokeSetter(Object row, String name, Long value) {
        try {
            row.getClass().getMethod(name, Long.class).invoke(row, value);
        } catch (ReflectiveOperationException e) {
            // roles/users 之类没有 owner 字段的实体直接跳过
        }
    }

    private static boolean hasField(Class<?> model, String field) {
        Class<?> cursor = model;
        while (cursor != null && cursor != Object.class) {
            try {
                cursor.getDeclaredField(field);
                return true;
            } catch (NoSuchFieldException e) {
                cursor = cursor.getSuperclass();
            }
        }
        return false;
    }
}

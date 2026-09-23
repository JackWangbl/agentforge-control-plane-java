package com.agentforge.controlplane.rag;

/**
 * 知识库权限。管理界面里的租户管理员看得到全部库；对话检索不因管理员身份放大范围。
 */
public final class KnowledgeAcl {

    private KnowledgeAcl() {}

    public static boolean canRetrieve(boolean member, boolean tenantVisible, boolean canRead) {
        return canRead && (member || tenantVisible);
    }

    public static boolean canViewInConsole(boolean member, boolean tenantVisible, boolean canRead, boolean tenantAdmin) {
        return tenantAdmin || canRetrieve(member, tenantVisible, canRead);
    }

    public static boolean canEditDocuments(String role, boolean canWrite) {
        return canWrite && ("owner".equals(role) || "editor".equals(role));
    }

    public static boolean canManage(String role, boolean canWrite) {
        return canWrite && "owner".equals(role);
    }
}

package com.agentforge.controlplane.domain;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;

/**
 * 对应 Python 版的 TenantOwnedMixin。tenant_id 默认 1（历史数据回填用的默认租户），
 * owner_id 可空表示“租户内公共资源”。
 */
@MappedSuperclass
public abstract class TenantOwnedEntity extends TimestampedEntity {

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId = 1L;

    @Column(name = "owner_id")
    private Long ownerId;

    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long tenantId) { this.tenantId = tenantId; }
    public Long getOwnerId() { return ownerId; }
    public void setOwnerId(Long ownerId) { this.ownerId = ownerId; }
}

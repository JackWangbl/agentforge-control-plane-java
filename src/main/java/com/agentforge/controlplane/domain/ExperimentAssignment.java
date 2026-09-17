package com.agentforge.controlplane.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/** 分流粘性靠这张表：同一个 unit_key 再来就复用上次的变体。 */
@Entity
@Table(name = "experiment_assignments")
public class ExperimentAssignment extends TenantOwnedEntity {

    @Column(name = "experiment_id", nullable = false)
    private Long experimentId;

    @Column(name = "variant_id", nullable = false)
    private Long variantId;

    @Column(name = "unit_key", length = 120, nullable = false)
    private String unitKey;

    @Column(name = "holdout", nullable = false)
    private boolean holdout = false;

    public Long getExperimentId() { return experimentId; }
    public void setExperimentId(Long experimentId) { this.experimentId = experimentId; }
    public Long getVariantId() { return variantId; }
    public void setVariantId(Long variantId) { this.variantId = variantId; }
    public String getUnitKey() { return unitKey; }
    public void setUnitKey(String unitKey) { this.unitKey = unitKey; }
    public boolean isHoldout() { return holdout; }
    public void setHoldout(boolean holdout) { this.holdout = holdout; }
}

package com.agentforge.controlplane.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Entity
@Table(name = "knowledge_bases")
public class KnowledgeBase extends TenantOwnedEntity {

    @Column(name = "name", length = 100, nullable = false)
    private String name;

    @Column(name = "description", length = 300, nullable = false)
    private String description = "";

    @Column(name = "embedding_model_id", nullable = false)
    private Long embeddingModelId;

    @Column(name = "vector_store_id", nullable = false)
    private Long vectorStoreId;

    @Column(name = "rerank_model_id")
    private Long rerankModelId;

    @Column(name = "visibility", length = 20, nullable = false)
    private String visibility = "private";

    @Column(name = "embedding_dimension")
    private Integer embeddingDimension;

    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    @Column(name = "top_k", nullable = false)
    private int topK = 5;

    @Column(name = "candidate_k", nullable = false)
    private int candidateK = 20;

    @Column(name = "score_threshold", nullable = false)
    private double scoreThreshold = 0.3;

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description == null ? "" : description; }
    public void setDescription(String description) { this.description = description == null ? "" : description; }
    public Long getEmbeddingModelId() { return embeddingModelId; }
    public void setEmbeddingModelId(Long embeddingModelId) { this.embeddingModelId = embeddingModelId; }
    public Long getVectorStoreId() { return vectorStoreId; }
    public void setVectorStoreId(Long vectorStoreId) { this.vectorStoreId = vectorStoreId; }
    public Long getRerankModelId() { return rerankModelId; }
    public void setRerankModelId(Long rerankModelId) { this.rerankModelId = rerankModelId; }
    public String getVisibility() { return visibility == null || visibility.isBlank() ? "private" : visibility; }
    public void setVisibility(String visibility) { this.visibility = visibility == null || visibility.isBlank() ? "private" : visibility; }
    public Integer getEmbeddingDimension() { return embeddingDimension; }
    public void setEmbeddingDimension(Integer embeddingDimension) { this.embeddingDimension = embeddingDimension; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public int getTopK() { return topK; }
    public void setTopK(int topK) { this.topK = topK; }
    public int getCandidateK() { return candidateK; }
    public void setCandidateK(int candidateK) { this.candidateK = candidateK; }
    public double getScoreThreshold() { return scoreThreshold; }
    public void setScoreThreshold(double scoreThreshold) { this.scoreThreshold = scoreThreshold; }
}

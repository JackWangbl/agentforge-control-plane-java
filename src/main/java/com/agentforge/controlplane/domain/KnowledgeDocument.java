package com.agentforge.controlplane.domain;

import com.agentforge.controlplane.domain.JsonConverters.MapConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;

import java.util.LinkedHashMap;
import java.util.Map;

@Entity
@Table(name = "knowledge_documents")
public class KnowledgeDocument extends TenantOwnedEntity {

    @Column(name = "knowledge_id", nullable = false)
    private Long knowledgeId;

    @Column(name = "filename", length = 255, nullable = false)
    private String filename = "";

    @Column(name = "media_type", length = 120, nullable = false)
    private String mediaType = "";

    @Column(name = "byte_size", nullable = false)
    private long byteSize;

    @Column(name = "storage_path", length = 500, nullable = false)
    private String storagePath = "";

    @Column(name = "status", length = 24, nullable = false)
    private String status = "queued";

    @Lob
    @Column(name = "error_message", columnDefinition = "text")
    private String errorMessage = "";

    @Convert(converter = MapConverter.class)
    @Column(name = "clean_summary", columnDefinition = "json")
    private Map<String, Object> cleanSummary = new LinkedHashMap<>();

    @Convert(converter = MapConverter.class)
    @Column(name = "profile", columnDefinition = "json")
    private Map<String, Object> profile = new LinkedHashMap<>();

    @Column(name = "strategy", length = 40, nullable = false)
    private String strategy = "";

    @Column(name = "strategy_reason", length = 500, nullable = false)
    private String strategyReason = "";

    @Column(name = "strategy_override", length = 40, nullable = false)
    private String strategyOverride = "";

    @Column(name = "chunk_count", nullable = false)
    private int chunkCount;

    @Column(name = "generation", nullable = false)
    private long generation;

    @Column(name = "content_hash", length = 64, nullable = false)
    private String contentHash = "";

    @Column(name = "embedding_model", length = 160, nullable = false)
    private String embeddingModel = "";

    @Column(name = "indexed_vector_store_id")
    private Long indexedVectorStoreId;

    public Long getKnowledgeId() { return knowledgeId; }
    public void setKnowledgeId(Long knowledgeId) { this.knowledgeId = knowledgeId; }
    public String getFilename() { return filename == null ? "" : filename; }
    public void setFilename(String filename) { this.filename = filename == null ? "" : filename; }
    public String getMediaType() { return mediaType == null ? "" : mediaType; }
    public void setMediaType(String mediaType) { this.mediaType = mediaType == null ? "" : mediaType; }
    public long getByteSize() { return byteSize; }
    public void setByteSize(long byteSize) { this.byteSize = byteSize; }
    public String getStoragePath() { return storagePath == null ? "" : storagePath; }
    public void setStoragePath(String storagePath) { this.storagePath = storagePath == null ? "" : storagePath; }
    public String getStatus() { return status == null ? "" : status; }
    public void setStatus(String status) { this.status = status == null ? "" : status; }
    public String getErrorMessage() { return errorMessage == null ? "" : errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage == null ? "" : errorMessage; }
    public Map<String, Object> getCleanSummary() { return cleanSummary == null ? Map.of() : cleanSummary; }
    public void setCleanSummary(Map<String, Object> cleanSummary) {
        this.cleanSummary = cleanSummary == null ? new LinkedHashMap<>() : cleanSummary;
    }
    public Map<String, Object> getProfile() { return profile == null ? Map.of() : profile; }
    public void setProfile(Map<String, Object> profile) {
        this.profile = profile == null ? new LinkedHashMap<>() : profile;
    }
    public String getStrategy() { return strategy == null ? "" : strategy; }
    public void setStrategy(String strategy) { this.strategy = strategy == null ? "" : strategy; }
    public String getStrategyReason() { return strategyReason == null ? "" : strategyReason; }
    public void setStrategyReason(String strategyReason) { this.strategyReason = strategyReason == null ? "" : strategyReason; }
    public String getStrategyOverride() { return strategyOverride == null ? "" : strategyOverride; }
    public void setStrategyOverride(String strategyOverride) { this.strategyOverride = strategyOverride == null ? "" : strategyOverride; }
    public int getChunkCount() { return chunkCount; }
    public void setChunkCount(int chunkCount) { this.chunkCount = chunkCount; }
    public long getGeneration() { return generation; }
    public void setGeneration(long generation) { this.generation = generation; }
    public String getContentHash() { return contentHash == null ? "" : contentHash; }
    public void setContentHash(String contentHash) { this.contentHash = contentHash == null ? "" : contentHash; }
    public String getEmbeddingModel() { return embeddingModel == null ? "" : embeddingModel; }
    public void setEmbeddingModel(String embeddingModel) { this.embeddingModel = embeddingModel == null ? "" : embeddingModel; }
    public Long getIndexedVectorStoreId() { return indexedVectorStoreId; }
    public void setIndexedVectorStoreId(Long indexedVectorStoreId) { this.indexedVectorStoreId = indexedVectorStoreId; }
}

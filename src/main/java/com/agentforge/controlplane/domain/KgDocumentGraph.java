package com.agentforge.controlplane.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;

@Entity
@Table(name = "kg_document_graphs")
public class KgDocumentGraph extends TimestampedEntity {

    @Column(name = "knowledge_id", nullable = false)
    private Long knowledgeId;

    @Column(name = "document_id", nullable = false, unique = true)
    private Long documentId;

    @Column(name = "generation", nullable = false)
    private long generation;

    @Lob
    @Column(name = "summary", columnDefinition = "text")
    private String summary = "";

    @Lob
    @Column(name = "entities_json", columnDefinition = "longtext")
    private String entitiesJson = "[]";

    @Lob
    @Column(name = "relations_json", columnDefinition = "longtext")
    private String relationsJson = "[]";

    public Long getKnowledgeId() { return knowledgeId; }
    public void setKnowledgeId(Long knowledgeId) { this.knowledgeId = knowledgeId; }
    public Long getDocumentId() { return documentId; }
    public void setDocumentId(Long documentId) { this.documentId = documentId; }
    public long getGeneration() { return generation; }
    public void setGeneration(long generation) { this.generation = generation; }
    public String getSummary() { return summary == null ? "" : summary; }
    public void setSummary(String summary) { this.summary = summary == null ? "" : summary; }
    public String getEntitiesJson() { return entitiesJson == null ? "[]" : entitiesJson; }
    public void setEntitiesJson(String entitiesJson) { this.entitiesJson = entitiesJson == null ? "[]" : entitiesJson; }
    public String getRelationsJson() { return relationsJson == null ? "[]" : relationsJson; }
    public void setRelationsJson(String relationsJson) { this.relationsJson = relationsJson == null ? "[]" : relationsJson; }
}

package com.agentforge.controlplane.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Entity
@Table(name = "dataset_cases")
public class DatasetCase extends TenantOwnedEntity {

    @Column(name = "dataset_id", nullable = false)
    private Long datasetId;

    @Column(name = "case_key", length = 80, nullable = false)
    private String caseKey = "";

    @Lob
    @Column(name = "input", columnDefinition = "text")
    private String input = "";

    @Lob
    @Column(name = "expected", columnDefinition = "text")
    private String expected = "";

    /** 意图标签等，评测时按标签聚合准确率靠它。 */
    @Convert(converter = JsonConverters.StringListConverter.class)
    @Column(name = "tags", columnDefinition = "json")
    private List<String> tags = new ArrayList<>();

    @Convert(converter = JsonConverters.MapConverter.class)
    @Column(name = "extra", columnDefinition = "json")
    private Map<String, Object> extra = new LinkedHashMap<>();

    /** Anthropic 评分器。空列表配合 extra.graders_explicit 表示明确不打分。 */
    @Convert(converter = JsonConverters.MapListConverter.class)
    @Column(name = "graders", columnDefinition = "json")
    private List<Map<String, Object>> graders = new ArrayList<>();

    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    public Long getDatasetId() { return datasetId; }
    public void setDatasetId(Long datasetId) { this.datasetId = datasetId; }
    public String getCaseKey() { return caseKey; }
    public void setCaseKey(String caseKey) { this.caseKey = caseKey; }
    public String getInput() { return input == null ? "" : input; }
    public void setInput(String input) { this.input = input; }
    public String getExpected() { return expected == null ? "" : expected; }
    public void setExpected(String expected) { this.expected = expected; }
    public List<String> getTags() { return tags; }
    public void setTags(List<String> tags) { this.tags = tags == null ? new ArrayList<>() : tags; }
    public Map<String, Object> getExtra() { return extra; }
    public void setExtra(Map<String, Object> extra) { this.extra = extra == null ? new LinkedHashMap<>() : extra; }
    public List<Map<String, Object>> getGraders() { return graders; }
    public void setGraders(List<Map<String, Object>> graders) {
        this.graders = graders == null ? new ArrayList<>() : graders;
    }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
}

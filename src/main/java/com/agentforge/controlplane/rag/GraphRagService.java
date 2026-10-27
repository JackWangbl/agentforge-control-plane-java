package com.agentforge.controlplane.rag;

import com.agentforge.controlplane.agent.AgentScopeRuntime;
import com.agentforge.controlplane.domain.KgDocumentGraph;
import com.agentforge.controlplane.domain.KnowledgeBase;
import com.agentforge.controlplane.domain.KnowledgeDocument;
import com.agentforge.controlplane.domain.ModelConfig;
import com.agentforge.controlplane.repo.KgDocumentGraphRepository;
import com.agentforge.controlplane.repo.ModelConfigRepository;
import com.agentforge.controlplane.util.Jsons;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 在混合检索之外，为选择了 GraphRAG 的知识库抽取实体关系并按局部/全局两种方式回答。 */
@Service
public class GraphRagService {

    private static final int MAX_CHUNKS = 16;

    public record Hit(long documentId, String heading, String content, String context, String mode) {}

    private final KgDocumentGraphRepository graphs;
    private final ModelConfigRepository models;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
    private final ObjectMapper json = new ObjectMapper();

    public GraphRagService(KgDocumentGraphRepository graphs, ModelConfigRepository models) {
        this.graphs = graphs;
        this.models = models;
    }

    public boolean ready(KnowledgeDocument document) {
        return graphs.findByDocumentId(document.getId())
                .filter(row -> row.getGeneration() == document.getGeneration())
                .isPresent();
    }

    public void deleteDocument(Long documentId) {
        graphs.deleteByDocumentId(documentId);
    }

    public void deleteKnowledge(Long knowledgeId) {
        graphs.deleteByKnowledgeId(knowledgeId);
    }

    public void build(KnowledgeBase base, KnowledgeDocument document, long generation, List<ChunkStrategySelector.Chunk> chunks) {
        ModelConfig model = models.findById(base.getGraphModelId()).orElse(null);
        if (model == null || !model.isEnabled() || !"chat".equals(model.getPurpose())) {
            throw new RagCallException("GraphRAG 需要一个已启用的对话模型", false);
        }
        List<ChunkStrategySelector.Chunk> sample = chunks.size() <= MAX_CHUNKS
                ? chunks : chunks.subList(0, MAX_CHUNKS);
        List<GraphRagGraphs.Extract> parts = new ArrayList<>();
        for (ChunkStrategySelector.Chunk chunk : sample) {
            String raw = complete(model, extractPrompt(chunk.content()));
            GraphRagGraphs.Extract extract = GraphRagGraphs.parse(raw);
            if (!extract.entities().isEmpty() || !extract.relations().isEmpty()) {
                parts.add(extract);
            }
        }
        GraphRagGraphs.Extract merged = GraphRagGraphs.merge(parts);
        if (merged.entities().isEmpty()) {
            throw new RagCallException("没有从文档里抽出实体，请检查图谱模型", true);
        }
        String summary = complete(model, summaryPrompt(document.getFilename(), merged));
        if (summary.isBlank()) {
            summary = fallbackSummary(merged);
        }
        KgDocumentGraph row = graphs.findByDocumentId(document.getId()).orElseGet(KgDocumentGraph::new);
        row.setKnowledgeId(base.getId());
        row.setDocumentId(document.getId());
        row.setGeneration(generation);
        row.setSummary(clip(summary, 1200));
        row.setEntitiesJson(Jsons.json(merged.entities().stream().map(this::entityMap).toList()));
        row.setRelationsJson(Jsons.json(merged.relations().stream().map(this::relationMap).toList()));
        graphs.save(row);
    }

    public List<Hit> search(KnowledgeBase base, String query) {
        List<KgDocumentGraph> rows = graphs.findByKnowledgeId(base.getId());
        List<Hit> local = new ArrayList<>();
        for (KgDocumentGraph row : rows) {
            List<GraphRagGraphs.Entity> entities = readEntities(row);
            List<GraphRagGraphs.Entity> matched = GraphRagGraphs.match(query, entities);
            if (matched.isEmpty()) {
                continue;
            }
            List<GraphRagGraphs.Relation> relations = readRelations(row);
            for (GraphRagGraphs.Entity entity : matched) {
                if (local.size() >= 8) {
                    break;
                }
                String related = relations.stream()
                        .filter(item -> GraphRagGraphs.key(item.source()).equals(GraphRagGraphs.key(entity.name()))
                                || GraphRagGraphs.key(item.target()).equals(GraphRagGraphs.key(entity.name())))
                        .map(item -> item.source() + " → " + item.target() + "：" + item.description())
                        .reduce((a, b) -> a + "\n" + b)
                        .orElse("");
                local.add(new Hit(row.getDocumentId(), entity.name(),
                        entity.type() + "：" + entity.description(), related.isBlank() ? row.getSummary() : related, "local"));
            }
        }
        if (!local.isEmpty()) {
            return local;
        }
        List<Hit> global = new ArrayList<>();
        for (KgDocumentGraph row : rows) {
            if (row.getSummary().isBlank() || global.size() >= 6) {
                continue;
            }
            global.add(new Hit(row.getDocumentId(), "社区摘要", row.getSummary(), row.getSummary(), "global"));
        }
        return global;
    }

    private Map<String, Object> entityMap(GraphRagGraphs.Entity entity) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("name", entity.name());
        item.put("type", entity.type());
        item.put("description", entity.description());
        return item;
    }

    private Map<String, Object> relationMap(GraphRagGraphs.Relation relation) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("source", relation.source());
        item.put("target", relation.target());
        item.put("description", relation.description());
        return item;
    }

    private List<GraphRagGraphs.Entity> readEntities(KgDocumentGraph row) {
        List<GraphRagGraphs.Entity> entities = new ArrayList<>();
        for (Map<String, Object> item : Jsons.mapList(Jsons.read(row.getEntitiesJson()))) {
            String name = Jsons.text(item.get("name")).strip();
            if (!name.isEmpty()) {
                entities.add(new GraphRagGraphs.Entity(name, Jsons.text(item.get("type")), Jsons.text(item.get("description"))));
            }
        }
        return entities;
    }

    private List<GraphRagGraphs.Relation> readRelations(KgDocumentGraph row) {
        List<GraphRagGraphs.Relation> relations = new ArrayList<>();
        for (Map<String, Object> item : Jsons.mapList(Jsons.read(row.getRelationsJson()))) {
            relations.add(new GraphRagGraphs.Relation(
                    Jsons.text(item.get("source")), Jsons.text(item.get("target")), Jsons.text(item.get("description"))));
        }
        return relations;
    }

    private String complete(ModelConfig model, String userPrompt) {
        String credential = AgentScopeRuntime.resolveCredential(model);
        if (credential.isBlank()) {
            throw new RagCallException("图谱模型没有密钥", false);
        }
        String url = AgentScopeRuntime.modelEndpoint(model) + "/chat/completions";
        try {
            String body = json.writeValueAsString(Map.of(
                    "model", model.getModelId(),
                    "temperature", 0,
                    "messages", List.of(
                            Map.of("role", "system", "content", "只输出 JSON 或摘要正文，不要加解释。"),
                            Map.of("role", "user", "content", userPrompt))));
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(45))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + credential)
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new RagCallException("图谱模型调用失败：" + response.statusCode(), true);
            }
            JsonNode content = json.readTree(response.body()).path("choices").path(0).path("message").path("content");
            return content.isMissingNode() ? "" : content.asText("");
        } catch (RagCallException e) {
            throw e;
        } catch (Exception e) {
            throw new RagCallException("图谱模型调用失败：" + e.getMessage(), true);
        }
    }

    private static String extractPrompt(String chunk) {
        return """
                从下面文本抽出实体和关系。只输出 JSON：
                {"entities":[{"name":"","type":"","description":""}],"relations":[{"source":"","target":"","description":""}]}
                实体最多 8 个，关系最多 8 条。没有就给空数组。
                文本：
                """ + clip(chunk, 900);
    }

    private static String summaryPrompt(String filename, GraphRagGraphs.Extract extract) {
        StringBuilder out = new StringBuilder();
        out.append("根据实体和关系，用不超过 200 字概括文档「").append(filename).append("」的主题和关键关系。只输出摘要。\n实体：\n");
        for (GraphRagGraphs.Entity entity : extract.entities()) {
            out.append("- ").append(entity.name()).append("（").append(entity.type()).append("）").append(entity.description()).append('\n');
        }
        out.append("关系：\n");
        for (GraphRagGraphs.Relation relation : extract.relations()) {
            out.append("- ").append(relation.source()).append(" → ").append(relation.target()).append("：").append(relation.description()).append('\n');
        }
        return out.toString();
    }

    private static String fallbackSummary(GraphRagGraphs.Extract extract) {
        StringBuilder out = new StringBuilder();
        for (GraphRagGraphs.Entity entity : extract.entities()) {
            if (out.length() > 800) {
                break;
            }
            out.append(entity.name()).append("：").append(entity.description()).append('\n');
        }
        return out.toString();
    }

    private static String clip(String value, int limit) {
        String text = value == null ? "" : value.strip();
        return text.length() <= limit ? text : text.substring(0, limit);
    }
}

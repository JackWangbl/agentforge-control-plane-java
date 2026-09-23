package com.agentforge.controlplane.rag;

import com.agentforge.controlplane.access.CurrentUser;
import com.agentforge.controlplane.access.CurrentUserHolder;
import com.agentforge.controlplane.domain.Agent;
import com.agentforge.controlplane.domain.KnowledgeBase;
import com.agentforge.controlplane.domain.KnowledgeDocument;
import com.agentforge.controlplane.domain.ModelConfig;
import com.agentforge.controlplane.domain.VectorStore;
import com.agentforge.controlplane.repo.KnowledgeBaseRepository;
import com.agentforge.controlplane.repo.KnowledgeDocumentRepository;
import com.agentforge.controlplane.repo.ModelConfigRepository;
import com.agentforge.controlplane.repo.VectorStoreRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class KnowledgeSearchService {

    private final KnowledgeBaseRepository bases;
    private final KnowledgeDocumentRepository documents;
    private final ModelConfigRepository models;
    private final VectorStoreRepository vectorStores;
    private final KnowledgeAccess access;
    private final EmbeddingClient embeddings;
    private final MilvusVectorStore milvus;
    private final HttpRerankClient rerankClient;
    private final DocumentCleaner cleaner = new DocumentCleaner();

    public KnowledgeSearchService(KnowledgeBaseRepository bases, KnowledgeDocumentRepository documents,
                                  ModelConfigRepository models, VectorStoreRepository vectorStores,
                                  KnowledgeAccess access, EmbeddingClient embeddings,
                                  MilvusVectorStore milvus, HttpRerankClient rerankClient) {
        this.bases = bases;
        this.documents = documents;
        this.models = models;
        this.vectorStores = vectorStores;
        this.access = access;
        this.embeddings = embeddings;
        this.milvus = milvus;
        this.rerankClient = rerankClient;
    }

    public boolean hasReadyDocuments(Agent agent) {
        for (KnowledgeBase base : selected(agent)) {
            if (base.isEnabled() && documents.countByKnowledgeIdAndStatus(base.getId(), "ready") > 0) {
                return true;
            }
        }
        return false;
    }

    public List<KnowledgeBase> selected(Agent agent) {
        List<Long> ids = agent == null ? List.of() : agent.getKnowledgeIds();
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        Long tenantId = agent.getTenantId();
        List<KnowledgeBase> rows = new ArrayList<>();
        for (KnowledgeBase base : bases.findAllById(ids)) {
            if (tenantId != null && tenantId.equals(base.getTenantId())) {
                rows.add(base);
            }
        }
        return rows;
    }

    public String promptHint(Agent agent) {
        if (!hasReadyDocuments(agent)) {
            return "";
        }
        return "用户问题涉及已绑定知识库中的事实、数字、条款或流程时，必须先调用 search_documents，再根据工具返回的原文回答。知识库没有命中时就说明不知道，不要用常识补全这些事实。";
    }

    public String searchForAgent(Agent agent, String query) {
        CurrentUser user = CurrentUserHolder.get();
        List<KnowledgeBase> visible = new ArrayList<>();
        for (KnowledgeBase base : selected(agent)) {
            if (!base.isEnabled()) {
                continue;
            }
            if (access.canRetrieve(user, base)) {
                visible.add(base);
            }
        }
        if (visible.isEmpty()) {
            return "当前没有你可以检索的文档。";
        }
        List<HitView> hits = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        for (KnowledgeBase base : visible) {
            try {
                hits.addAll(searchBase(base, query, false).hits());
            } catch (RagCallException e) {
                errors.add(base.getName() + "：" + e.getMessage());
            }
        }
        hits.sort(Comparator.comparingDouble(HitView::sortScore).reversed());
        if (hits.size() > 10) {
            hits = hits.subList(0, 10);
        }
        if (hits.isEmpty() && errors.isEmpty()) {
            return "知识库中没有与该问题足够相关的内容。";
        }
        StringBuilder out = new StringBuilder();
        int index = 1;
        for (HitView hit : hits) {
            if (out.length() > 0) {
                out.append("\n\n");
            }
            out.append("【").append(index++).append("】")
                    .append(hit.baseName()).append(" / ").append(hit.filename()).append(" / ").append(hit.heading())
                    .append("\n").append(hit.context());
        }
        if (!errors.isEmpty()) {
            if (out.length() > 0) {
                out.append("\n\n");
            }
            out.append("以下知识库检索失败：").append(String.join("；", errors));
        }
        return out.toString();
    }

    public Map<String, Object> debugSearch(KnowledgeBase base, String query) {
        SearchPack pack = searchBase(base, query, true);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("vector_store", pack.vectorStore());
        body.put("retriever", "dense+bm25");
        body.put("reranker", pack.reranker());
        body.put("hits", pack.hits().stream().map(HitView::toMap).toList());
        return body;
    }

    private SearchPack searchBase(KnowledgeBase base, String query, boolean debug) {
        List<KnowledgeDocument> ready = documents.findByKnowledgeIdAndStatus(base.getId(), "ready");
        if (ready.isEmpty()) {
            return new SearchPack(vectorName(base), rerankName(base), List.of());
        }
        ModelConfig model = models.findById(base.getEmbeddingModelId())
                .filter(row -> row.isEnabled() && "embedding".equals(row.getPurpose()))
                .orElseThrow(() -> new RagCallException("向量模型不可用", false));
        VectorStore store = vectorStores.findById(base.getVectorStoreId())
                .orElseThrow(() -> new RagCallException("向量数据库不存在", true));
        if (!store.isEnabled()) {
            throw new RagCallException("向量数据库已停用", true);
        }
        String normalized = cleaner.clean(query == null ? "" : query, "text/plain").text();
        if (normalized.isBlank()) {
            normalized = query == null ? "" : query.strip();
        }
        float[] vector = embeddings.embed(model, List.of(normalized)).get(0);
        String filter = filter(base, ready);
        int candidateK = Math.max(10, Math.min(50, base.getCandidateK()));
        int topK = Math.max(1, Math.min(10, base.getTopK()));
        long tenantId = base.getTenantId() == null ? 1L : base.getTenantId();
        List<MilvusVectorStore.Hit> found = milvus.hybridSearch(
                store, tenantId, base.getId(), filter, vector, normalized, candidateK);
        String reranker = "none";
        List<HitView> views = new ArrayList<>();
        if (base.getRerankModelId() == null) {
            for (MilvusVectorStore.Hit hit : found) {
                if (views.size() >= topK) {
                    break;
                }
                views.add(view(base, ready, hit, null, 1.0 / (60 + hit.rank())));
            }
        } else {
            ModelConfig rerank = models.findById(base.getRerankModelId())
                    .orElseThrow(() -> new RagCallException("重排序模型不存在", true));
            if (!rerank.isEnabled() || !"rerank".equals(rerank.getPurpose())) {
                throw new RagCallException("重排序模型已停用", true);
            }
            reranker = rerank.getName();
            List<String> docs = found.stream().map(MilvusVectorStore.Hit::content).toList();
            List<HttpRerankClient.Scored> scored = rerankClient.rerank(rerank, normalized, docs, topK);
            for (HttpRerankClient.Scored item : scored) {
                if (item.score() < base.getScoreThreshold()) {
                    continue;
                }
                MilvusVectorStore.Hit hit = found.get(item.index());
                views.add(view(base, ready, hit, item.score(), item.score()));
                if (views.size() >= topK) {
                    break;
                }
            }
        }
        if (!debug) {
            return new SearchPack(store.getName(), reranker, views);
        }
        return new SearchPack(store.getName(), reranker, views);
    }

    private static HitView view(KnowledgeBase base, List<KnowledgeDocument> ready, MilvusVectorStore.Hit hit,
                                Double rerankScore, double sortScore) {
        String filename = "";
        for (KnowledgeDocument document : ready) {
            if (document.getId() != null && document.getId() == hit.documentId()) {
                filename = document.getFilename();
                break;
            }
        }
        return new HitView(base.getName(), filename, hit.heading(), hit.content(), hit.context(),
                hit.rank(), hit.score(), rerankScore, sortScore);
    }

    private static String filter(KnowledgeBase base, List<KnowledgeDocument> ready) {
        long tenantId = base.getTenantId() == null ? 1L : base.getTenantId();
        StringBuilder expr = new StringBuilder("tenant_id == ").append(tenantId)
                .append(" && knowledge_id == ").append(base.getId()).append(" && (");
        for (int i = 0; i < ready.size(); i++) {
            if (i > 0) {
                expr.append(" || ");
            }
            KnowledgeDocument document = ready.get(i);
            expr.append("(document_id == ").append(document.getId())
                    .append(" && generation == ").append(document.getGeneration()).append(')');
        }
        expr.append(')');
        return expr.toString();
    }

    private String vectorName(KnowledgeBase base) {
        return vectorStores.findById(base.getVectorStoreId()).map(VectorStore::getName).orElse("");
    }

    private String rerankName(KnowledgeBase base) {
        if (base.getRerankModelId() == null) {
            return "none";
        }
        return models.findById(base.getRerankModelId()).map(ModelConfig::getName).orElse("none");
    }

    private record SearchPack(String vectorStore, String reranker, List<HitView> hits) {}

    public record HitView(String baseName, String filename, String heading, String content, String context, int rank,
                          float rrfScore, Double rerankScore, double sortScore) {
        Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("knowledge", baseName);
            map.put("filename", filename);
            map.put("heading", heading);
            map.put("content", content);
            map.put("context", context == null || context.isBlank() ? content : context);
            map.put("rank", rank);
            map.put("rrf_score", rrfScore);
            map.put("rerank_score", rerankScore);
            return map;
        }
    }
}

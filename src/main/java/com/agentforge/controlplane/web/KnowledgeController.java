package com.agentforge.controlplane.web;

import com.agentforge.controlplane.access.CurrentUser;
import com.agentforge.controlplane.access.RequirePermission;
import com.agentforge.controlplane.access.ResourceAccessService;
import com.agentforge.controlplane.domain.Agent;
import com.agentforge.controlplane.domain.KnowledgeBase;
import com.agentforge.controlplane.domain.KnowledgeDocument;
import com.agentforge.controlplane.domain.ModelConfig;
import com.agentforge.controlplane.domain.VectorStore;
import com.agentforge.controlplane.rag.DocumentExtractor;
import com.agentforge.controlplane.rag.KnowledgeAccess;
import com.agentforge.controlplane.rag.KnowledgeIndexService;
import com.agentforge.controlplane.rag.KnowledgeSearchService;
import com.agentforge.controlplane.rag.MilvusVectorStore;
import com.agentforge.controlplane.rag.RagCallException;
import com.agentforge.controlplane.repo.AgentRepository;
import com.agentforge.controlplane.repo.KnowledgeBaseRepository;
import com.agentforge.controlplane.repo.KnowledgeDocumentRepository;
import com.agentforge.controlplane.repo.ModelConfigRepository;
import com.agentforge.controlplane.repo.VectorStoreRepository;
import com.agentforge.controlplane.util.Jsons;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@RestController
public class KnowledgeController {

    private final ResourceAccessService access;
    private final VectorStoreRepository vectorStores;
    private final KnowledgeBaseRepository bases;
    private final KnowledgeAccess knowledgeAccess;
    private final KnowledgeDocumentRepository documents;
    private final ModelConfigRepository models;
    private final AgentRepository agents;
    private final KnowledgeIndexService index;
    private final KnowledgeSearchService search;
    private final MilvusVectorStore milvus;

    public KnowledgeController(ResourceAccessService access, VectorStoreRepository vectorStores,
                               KnowledgeBaseRepository bases, KnowledgeAccess knowledgeAccess,
                               KnowledgeDocumentRepository documents, ModelConfigRepository models,
                               AgentRepository agents, KnowledgeIndexService index, KnowledgeSearchService search,
                               MilvusVectorStore milvus) {
        this.access = access;
        this.vectorStores = vectorStores;
        this.bases = bases;
        this.knowledgeAccess = knowledgeAccess;
        this.documents = documents;
        this.models = models;
        this.agents = agents;
        this.index = index;
        this.search = search;
        this.milvus = milvus;
    }

    @RequirePermission({"tenant:admin", "vector:read"})
    @GetMapping("/api/vector-stores")
    public List<Map<String, Object>> listVectorStores(CurrentUser user) {
        return vectorStores.findByTenantIdOrderByIdDesc(user.getTenantId()).stream().map(this::dumpVector).toList();
    }

    @RequirePermission({"tenant:admin", "vector:read"})
    @ResponseStatus(HttpStatus.CREATED)
    @PostMapping("/api/vector-stores")
    public Map<String, Object> createVectorStore(CurrentUser user, @RequestBody Map<String, Object> payload) {
        String type = text(payload.get("type"), "milvus");
        if (!"milvus".equalsIgnoreCase(type)) {
            throw ApiException.unprocessable("向量数据库类型只支持 milvus");
        }
        String name = requiredName(payload.get("name"));
        ensureVectorName(user.getTenantId(), name, null);
        VectorStore row = new VectorStore();
        row.setName(name);
        row.setType("milvus");
        row.setUri(required(payload.get("uri"), "请填写 Milvus 地址"));
        row.setDatabaseName(text(payload.get("database_name"), "default"));
        row.setToken(text(payload.get("token"), ""));
        row.setEnabled(bool(payload.get("enabled"), true));
        row.setDefault(!hasDefault(user.getTenantId()));
        access.stampOwner(row, user);
        return dumpVector(vectorStores.save(row));
    }

    @RequirePermission({"tenant:admin", "vector:read"})
    @PostMapping("/api/vector-stores/{id}/test")
    public Map<String, Object> testVectorStore(CurrentUser user, @PathVariable Long id) {
        VectorStore row = vector(user, id);
        try {
            milvus.probe(row);
            return Map.of("ok", true, "message", row.getName() + " 可以连接");
        } catch (RagCallException e) {
            throw e.isUnavailable() ? ApiException.unavailable(e.getMessage()) : ApiException.unprocessable(e.getMessage());
        }
    }

    @RequirePermission({"tenant:admin", "vector:read"})
    @PutMapping("/api/vector-stores/{id}")
    public Map<String, Object> updateVectorStore(CurrentUser user, @PathVariable Long id, @RequestBody Map<String, Object> payload) {
        VectorStore row = vector(user, id);
        if (payload.containsKey("name")) {
            String name = requiredName(payload.get("name"));
            ensureVectorName(user.getTenantId(), name, row.getId());
            row.setName(name);
        }
        if (payload.containsKey("uri")) {
            row.setUri(required(payload.get("uri"), "请填写 Milvus 地址"));
        }
        if (payload.containsKey("database_name")) {
            row.setDatabaseName(text(payload.get("database_name"), "default"));
        }
        if (payload.containsKey("token") && !text(payload.get("token"), "").isBlank()) {
            row.setToken(text(payload.get("token"), ""));
        }
        if (payload.containsKey("enabled")) {
            row.setEnabled(bool(payload.get("enabled"), row.isEnabled()));
        }
        if (payload.containsKey("is_default") && bool(payload.get("is_default"), false)) {
            markDefault(user.getTenantId(), row);
        }
        return dumpVector(vectorStores.save(row));
    }

    @RequirePermission({"tenant:admin", "vector:read"})
    @DeleteMapping("/api/vector-stores/{id}")
    public Map<String, Object> deleteVectorStore(CurrentUser user, @PathVariable Long id) {
        VectorStore row = vector(user, id);
        if (!bases.findByVectorStoreId(row.getId()).isEmpty()) {
            throw ApiException.conflict("仍有知识库使用该向量数据库");
        }
        vectorStores.delete(row);
        return Map.of("deleted", true);
    }

    @RequirePermission("knowledge:read")
    @GetMapping("/api/knowledge")
    public List<Map<String, Object>> listKnowledge(CurrentUser user) {
        return knowledgeAccess.listForConsole(user).stream().map(row -> dumpBase(user, row)).toList();
    }

    @RequirePermission("knowledge:write")
    @ResponseStatus(HttpStatus.CREATED)
    @PostMapping("/api/knowledge")
    public Map<String, Object> createKnowledge(CurrentUser user, @RequestBody Map<String, Object> payload) {
        String name = requiredName(payload.get("name"));
        ensureBaseName(user.getTenantId(), name, null);
        KnowledgeBase row = new KnowledgeBase();
        row.setName(name);
        row.setDescription(clip(text(payload.get("description"), ""), 300));
        row.setVisibility(KnowledgeAccess.normalizeVisibility(text(payload.get("visibility"), "private")));
        row.setVectorStoreId(knowledgeAccess.defaultVectorStore(user.getTenantId()).getId());
        applyBaseLinks(user, row, payload, true);
        applyBaseKnobs(row, payload);
        access.stampOwner(row, user);
        KnowledgeBase saved = bases.save(row);
        knowledgeAccess.addOwner(saved, user);
        return dumpBase(user, saved);
    }

    @RequirePermission("knowledge:write")
    @PutMapping("/api/knowledge/{id}")
    public Map<String, Object> updateKnowledge(CurrentUser user, @PathVariable Long id, @RequestBody Map<String, Object> payload) {
        KnowledgeBase row = knowledgeAccess.requireManage(user, id);
        Long oldModel = row.getEmbeddingModelId();
        Long oldStore = row.getVectorStoreId();
        Integer oldDim = row.getEmbeddingDimension();
        if (payload.containsKey("name")) {
            String name = requiredName(payload.get("name"));
            ensureBaseName(user.getTenantId(), name, row.getId());
            row.setName(name);
        }
        if (payload.containsKey("description")) {
            row.setDescription(clip(text(payload.get("description"), ""), 300));
        }
        if (payload.containsKey("visibility")) {
            row.setVisibility(KnowledgeAccess.normalizeVisibility(text(payload.get("visibility"), row.getVisibility())));
        }
        applyBaseLinks(user, row, payload, false);
        applyBaseKnobs(row, payload);
        boolean rebuild = !oldModel.equals(row.getEmbeddingModelId()) || !oldStore.equals(row.getVectorStoreId());
        if (rebuild) {
            row.setEmbeddingDimension(null);
            ModelConfig model = models.findById(row.getEmbeddingModelId()).orElse(null);
            if (model != null && oldDim != null && payload.containsKey("embedding_dimension")) {
                row.setEmbeddingDimension(oldDim);
            }
        }
        bases.save(row);
        if (rebuild) {
            VectorStore store = vectorStores.findById(row.getVectorStoreId()).orElse(null);
            if (store != null) {
                try {
                    milvus.dropCollection(store, user.getTenantId(), row.getId());
                } catch (RagCallException e) {
                    throw e.isUnavailable() ? ApiException.unavailable(e.getMessage()) : ApiException.unprocessable(e.getMessage());
                }
            }
            for (KnowledgeDocument document : documents.findByKnowledgeIdOrderByIdDesc(row.getId())) {
                document.setContentHash("");
                documents.save(document);
            }
            index.enqueueAll(row.getId(), true);
        }
        return dumpBase(user, row);
    }

    @RequirePermission("knowledge:write")
    @DeleteMapping("/api/knowledge/{id}")
    public Map<String, Object> deleteKnowledge(CurrentUser user, @PathVariable Long id,
                                               @RequestParam(name = "force", defaultValue = "false") boolean force) {
        KnowledgeBase row = knowledgeAccess.requireManage(user, id);
        long count = documents.countByKnowledgeId(row.getId());
        if (count > 0 && !force) {
            throw ApiException.conflict("知识库还有文档，确认删除请带 force=true");
        }
        VectorStore store = vectorStores.findById(row.getVectorStoreId()).orElse(null);
        boolean wroteVectors = row.getEmbeddingDimension() != null
                || documents.findByKnowledgeIdOrderByIdDesc(row.getId()).stream()
                .anyMatch(doc -> doc.getChunkCount() > 0 || "ready".equals(doc.getStatus()));
        if (store != null && wroteVectors) {
            try {
                milvus.dropCollection(store, user.getTenantId(), row.getId());
            } catch (RagCallException e) {
                throw ApiException.unavailable(e.getMessage());
            }
        }
        for (KnowledgeDocument document : documents.findByKnowledgeIdOrderByIdDesc(row.getId())) {
            deleteFile(document);
            documents.delete(document);
        }
        for (Agent agent : agents.findByTenantIdOrderByIdAsc(user.getTenantId())) {
            List<Long> ids = new ArrayList<>(agent.getKnowledgeIds() == null ? List.of() : agent.getKnowledgeIds());
            if (ids.remove(row.getId())) {
                agent.setKnowledgeIds(ids);
                agents.save(agent);
            }
        }
        knowledgeAccess.deleteMembers(row.getId());
        bases.delete(row);
        return Map.of("deleted", true);
    }

    @RequirePermission("knowledge:read")
    @GetMapping("/api/knowledge/{id}/documents")
    public List<Map<String, Object>> listDocuments(CurrentUser user, @PathVariable Long id) {
        KnowledgeBase row = base(user, id);
        return documents.findByKnowledgeIdOrderByIdDesc(row.getId()).stream().map(this::dumpDocument).toList();
    }

    @RequirePermission("knowledge:write")
    @ResponseStatus(HttpStatus.CREATED)
    @PostMapping("/api/knowledge/{id}/documents")
    public List<Map<String, Object>> upload(CurrentUser user, @PathVariable Long id,
                                            @RequestParam("file") List<MultipartFile> files) {
        KnowledgeBase row = knowledgeAccess.requireDocuments(user, id);
        if (files == null || files.isEmpty()) {
            throw ApiException.badRequest("请上传文件");
        }
        if (files.size() > 10) {
            throw ApiException.unprocessable("单次最多上传 10 个文件");
        }
        List<Map<String, Object>> created = new ArrayList<>();
        for (MultipartFile file : files) {
            String filename = file.getOriginalFilename() == null ? "document.txt" : file.getOriginalFilename();
            String ext = DocumentExtractor.extension(filename);
            if (!DocumentExtractor.ALLOWED.contains(ext)) {
                throw ApiException.unsupportedMedia("不支持的文件类型：" + filename);
            }
            if (file.getSize() > 15L * 1024 * 1024) {
                throw ApiException.payloadTooLarge("单个文件不能超过 15MB");
            }
            KnowledgeDocument document = new KnowledgeDocument();
            document.setKnowledgeId(row.getId());
            document.setFilename(safeName(filename));
            document.setMediaType(DocumentExtractor.mediaType(filename));
            document.setByteSize(file.getSize());
            document.setStatus("queued");
            access.stampOwner(document, user);
            document.setTenantId(row.getTenantId());
            documents.save(document);
            try {
                String relative = "tenants/" + row.getTenantId() + "/knowledge/" + row.getId()
                        + "/" + document.getId() + "-" + safeName(filename);
                java.nio.file.Path path = index.resolve(relative);
                Files.createDirectories(path.getParent());
                file.transferTo(path);
                document.setStoragePath(relative);
                documents.save(document);
            } catch (Exception e) {
                document.setStatus("failed");
                document.setErrorMessage("保存原始文件失败：" + e.getMessage());
                documents.save(document);
            }
            if ("queued".equals(document.getStatus())) {
                index.enqueue(document.getId());
            }
            created.add(dumpDocument(document));
        }
        return created;
    }

    @RequirePermission("knowledge:read")
    @GetMapping("/api/knowledge/{id}/documents/{docId}")
    public Map<String, Object> documentDetail(CurrentUser user, @PathVariable Long id, @PathVariable Long docId) {
        KnowledgeBase row = base(user, id);
        KnowledgeDocument document = document(row, docId);
        Map<String, Object> body = dumpDocument(document);
        if (document.getGeneration() > 0) {
            VectorStore store = vectorStores.findById(row.getVectorStoreId()).orElse(null);
            if (store == null) {
                throw ApiException.unavailable("向量数据库不存在");
            }
            try {
                body.put("chunks", milvus.preview(store, row.getTenantId(), row.getId(), document.getId(), document.getGeneration(), 20));
            } catch (RagCallException e) {
                throw ApiException.unavailable(e.getMessage());
            }
        } else {
            body.put("chunks", List.of());
        }
        return body;
    }

    @RequirePermission("knowledge:write")
    @PostMapping("/api/knowledge/{id}/documents/{docId}/reindex")
    public Map<String, Object> reindex(CurrentUser user, @PathVariable Long id, @PathVariable Long docId,
                                       @RequestBody(required = false) Map<String, Object> payload) {
        KnowledgeBase row = knowledgeAccess.requireDocuments(user, id);
        KnowledgeDocument document = document(row, docId);
        if ("processing".equals(document.getStatus()) || "queued".equals(document.getStatus())) {
            throw ApiException.conflict("文档正在处理");
        }
        if (payload != null && payload.containsKey("strategy")) {
            String strategy = text(payload.get("strategy"), "");
            document.setStrategyOverride(strategy);
            documents.save(document);
        }
        document.setStatus("queued");
        documents.save(document);
        index.enqueue(document.getId());
        return dumpDocument(document);
    }

    @RequirePermission("knowledge:write")
    @DeleteMapping("/api/knowledge/{id}/documents/{docId}")
    public Map<String, Object> deleteDocument(CurrentUser user, @PathVariable Long id, @PathVariable Long docId) {
        KnowledgeBase row = knowledgeAccess.requireDocuments(user, id);
        KnowledgeDocument document = document(row, docId);
        VectorStore store = vectorStores.findById(row.getVectorStoreId()).orElse(null);
        if (store != null && document.getGeneration() > 0) {
            try {
                milvus.deleteDocument(store, row.getTenantId(), row.getId(), document.getId());
            } catch (RagCallException e) {
                throw ApiException.unavailable(e.getMessage());
            }
        }
        deleteFile(document);
        documents.delete(document);
        return Map.of("deleted", true);
    }

    @RequirePermission("knowledge:read")
    @PostMapping("/api/knowledge/{id}/search")
    public Map<String, Object> search(CurrentUser user, @PathVariable Long id, @RequestBody Map<String, Object> payload) {
        KnowledgeBase row = base(user, id);
        try {
            return search.debugSearch(row, text(payload.get("query"), ""));
        } catch (RagCallException e) {
            throw e.isUnavailable() ? ApiException.unavailable(e.getMessage()) : ApiException.unprocessable(e.getMessage());
        }
    }

    @RequirePermission("knowledge:read")
    @GetMapping("/api/knowledge/{id}/members")
    public Map<String, Object> members(CurrentUser user, @PathVariable Long id) {
        KnowledgeBase row = knowledgeAccess.requireConsole(user, id);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("members", knowledgeAccess.listMembers(user, row));
        body.put("candidates", knowledgeAccess.canManage(user, row) ? knowledgeAccess.shareCandidates(user) : List.of());
        return body;
    }

    @RequirePermission("knowledge:write")
    @PutMapping("/api/knowledge/{id}/members")
    public Map<String, Object> grantMember(CurrentUser user, @PathVariable Long id, @RequestBody Map<String, Object> payload) {
        KnowledgeBase row = knowledgeAccess.requireConsole(user, id);
        return knowledgeAccess.grant(user, row, Jsons.asLong(payload.get("user_id")), text(payload.get("role"), "viewer"));
    }

    @RequirePermission("knowledge:write")
    @DeleteMapping("/api/knowledge/{id}/members/{userId}")
    public Map<String, Object> revokeMember(CurrentUser user, @PathVariable Long id, @PathVariable Long userId) {
        KnowledgeBase row = knowledgeAccess.requireConsole(user, id);
        knowledgeAccess.revoke(user, row, userId);
        return Map.of("deleted", true);
    }

    private void applyBaseLinks(CurrentUser user, KnowledgeBase row, Map<String, Object> payload, boolean creating) {
        if (creating || payload.containsKey("embedding_model_id")) {
            Long modelId = Jsons.asLong(payload.get("embedding_model_id"));
            ModelConfig model = modelId == null ? null : models.findById(modelId).orElse(null);
            if (model == null || !user.getTenantId().equals(model.getTenantId()) || !"embedding".equals(model.getPurpose())) {
                throw ApiException.unprocessable("请选择本租户已启用的向量模型");
            }
            if (row.getEmbeddingDimension() != null && payload.containsKey("embedding_model_id")
                    && modelId.equals(row.getEmbeddingModelId())) {
                // 同一个模型不改维度
            }
            row.setEmbeddingModelId(modelId);
        }
        if (creating || payload.containsKey("rerank_model_id")) {
            Object raw = payload.get("rerank_model_id");
            if (raw == null || text(raw, "").isBlank()) {
                row.setRerankModelId(null);
            } else {
                Long rerankId = Jsons.asLong(raw);
                ModelConfig rerank = rerankId == null ? null : models.findById(rerankId).orElse(null);
                if (rerank == null || !user.getTenantId().equals(rerank.getTenantId()) || !"rerank".equals(rerank.getPurpose())) {
                    throw ApiException.unprocessable("请选择本租户的重排序模型");
                }
                row.setRerankModelId(rerankId);
            }
        }
    }

    private static void applyBaseKnobs(KnowledgeBase row, Map<String, Object> payload) {
        if (payload.containsKey("enabled")) {
            row.setEnabled(bool(payload.get("enabled"), row.isEnabled()));
        }
        if (payload.containsKey("top_k")) {
            int value = number(payload.get("top_k"), row.getTopK());
            if (value < 1 || value > 10) {
                throw ApiException.unprocessable("top_k 范围是 1 到 10");
            }
            row.setTopK(value);
        }
        if (payload.containsKey("candidate_k")) {
            int value = number(payload.get("candidate_k"), row.getCandidateK());
            if (value < 10 || value > 50) {
                throw ApiException.unprocessable("candidate_k 范围是 10 到 50");
            }
            row.setCandidateK(value);
        }
        if (payload.containsKey("score_threshold")) {
            double value = payload.get("score_threshold") instanceof Number n
                    ? n.doubleValue() : row.getScoreThreshold();
            if (value < 0 || value > 1) {
                throw ApiException.unprocessable("score_threshold 范围是 0 到 1");
            }
            row.setScoreThreshold(value);
        }
    }

    private Map<String, Object> dumpBase(CurrentUser user, KnowledgeBase row) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", row.getId());
        data.put("name", row.getName());
        data.put("description", row.getDescription());
        data.put("visibility", row.getVisibility());
        data.put("relation", knowledgeAccess.relation(user, row));
        data.put("role", knowledgeAccess.roleOf(user, row));
        data.put("can_manage", knowledgeAccess.canManage(user, row));
        data.put("can_edit", knowledgeAccess.canEditDocuments(user, row));
        data.put("embedding_model_id", row.getEmbeddingModelId());
        data.put("vector_store_id", row.getVectorStoreId());
        data.put("rerank_model_id", row.getRerankModelId());
        data.put("embedding_dimension", row.getEmbeddingDimension());
        data.put("enabled", row.isEnabled());
        data.put("top_k", row.getTopK());
        data.put("candidate_k", row.getCandidateK());
        data.put("score_threshold", row.getScoreThreshold());
        data.put("document_count", documents.countByKnowledgeId(row.getId()));
        data.put("ready_documents", documents.countByKnowledgeIdAndStatus(row.getId(), "ready"));
        data.put("embedding_model", models.findById(row.getEmbeddingModelId()).map(ModelConfig::getName).orElse(""));
        data.put("vector_store_name", vectorStores.findById(row.getVectorStoreId()).map(VectorStore::getName).orElse(""));
        data.put("rerank_name", row.getRerankModelId() == null ? "" : models.findById(row.getRerankModelId()).map(ModelConfig::getName).orElse(""));
        return data;
    }

    private Map<String, Object> dumpDocument(KnowledgeDocument row) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", row.getId());
        data.put("knowledge_id", row.getKnowledgeId());
        data.put("filename", row.getFilename());
        data.put("media_type", row.getMediaType());
        data.put("byte_size", row.getByteSize());
        data.put("status", row.getStatus());
        data.put("error_message", row.getErrorMessage());
        data.put("clean_summary", row.getCleanSummary());
        data.put("profile", row.getProfile());
        data.put("strategy", row.getStrategy());
        data.put("strategy_reason", row.getStrategyReason());
        data.put("strategy_override", row.getStrategyOverride());
        data.put("chunk_count", row.getChunkCount());
        data.put("generation", row.getGeneration());
        return data;
    }

    private Map<String, Object> dumpVector(VectorStore row) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", row.getId());
        data.put("name", row.getName());
        data.put("type", row.getType());
        data.put("uri", row.getUri());
        data.put("database_name", row.getDatabaseName());
        data.put("enabled", row.isEnabled());
        data.put("is_default", row.isDefault());
        data.put("has_token", row.getToken() != null && !row.getToken().isBlank());
        return data;
    }

    private VectorStore vector(CurrentUser user, Long id) {
        VectorStore row = vectorStores.findById(id).orElseThrow(() -> ApiException.notFound("向量数据库不存在"));
        if (!user.getTenantId().equals(row.getTenantId())) {
            throw ApiException.notFound("向量数据库不存在");
        }
        return row;
    }

    private KnowledgeBase base(CurrentUser user, Long id) {
        return knowledgeAccess.requireConsole(user, id);
    }

    private KnowledgeDocument document(KnowledgeBase base, Long docId) {
        KnowledgeDocument row = documents.findById(docId).orElseThrow(() -> ApiException.notFound("文档不存在"));
        if (!base.getId().equals(row.getKnowledgeId())) {
            throw ApiException.notFound("文档不存在");
        }
        return row;
    }

    private void ensureVectorName(Long tenantId, String name, Long self) {
        vectorStores.findByTenantIdAndName(tenantId, name).ifPresent(found -> {
            if (self == null || !self.equals(found.getId())) {
                throw ApiException.conflict("向量数据库名称已存在");
            }
        });
    }

    private boolean hasDefault(Long tenantId) {
        return vectorStores.findByTenantIdOrderByIdDesc(tenantId).stream().anyMatch(VectorStore::isDefault);
    }

    private void markDefault(Long tenantId, VectorStore chosen) {
        for (VectorStore other : vectorStores.findByTenantIdOrderByIdDesc(tenantId)) {
            if (other.isDefault() && !other.getId().equals(chosen.getId())) {
                other.setDefault(false);
                vectorStores.save(other);
            }
        }
        chosen.setDefault(true);
    }

    private void ensureBaseName(Long tenantId, String name, Long self) {
        bases.findByTenantIdAndName(tenantId, name).ifPresent(found -> {
            if (self == null || !self.equals(found.getId())) {
                throw ApiException.conflict("知识库名称已存在");
            }
        });
    }

    private void deleteFile(KnowledgeDocument document) {
        try {
            if (!document.getStoragePath().isBlank()) {
                Files.deleteIfExists(index.resolve(document.getStoragePath()));
            }
        } catch (Exception ignored) {
        }
    }

    private static String requiredName(Object value) {
        String name = required(value, "请填写名称").strip();
        if (name.length() < 2 || name.length() > 100) {
            throw ApiException.unprocessable("名称长度需要在 2 到 100 之间");
        }
        return name;
    }

    private static String required(Object value, String message) {
        String text = text(value, "");
        if (text.isBlank()) {
            throw ApiException.unprocessable(message);
        }
        return text;
    }

    private static String text(Object value, String fallback) {
        if (value == null) {
            return fallback;
        }
        String text = String.valueOf(value).strip();
        return text.isEmpty() ? fallback : text;
    }

    private static boolean bool(Object value, boolean fallback) {
        if (value instanceof Boolean flag) {
            return flag;
        }
        if (value == null) {
            return fallback;
        }
        return "true".equalsIgnoreCase(String.valueOf(value)) || "1".equals(String.valueOf(value));
    }

    private static int number(Object value, int fallback) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (Exception e) {
            return fallback;
        }
    }

    private static String clip(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }

    static String safeName(String filename) {
        String name = filename == null ? "file" : filename.replace('\\', '/');
        int slash = name.lastIndexOf('/');
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        name = name.replaceAll("[^\\w.\\u4e00-\\u9fff-]+", "-");
        if (name.isBlank() || ".".equals(name)) {
            name = "file.txt";
        }
        return name.length() > 80 ? name.substring(name.length() - 80) : name.toLowerCase(Locale.ROOT);
    }
}

package com.agentforge.controlplane.rag;

import com.agentforge.controlplane.config.AppSettings;
import com.agentforge.controlplane.domain.KnowledgeBase;
import com.agentforge.controlplane.domain.KnowledgeDocument;
import com.agentforge.controlplane.domain.ModelConfig;
import com.agentforge.controlplane.domain.VectorStore;
import com.agentforge.controlplane.repo.KnowledgeBaseRepository;
import com.agentforge.controlplane.repo.KnowledgeDocumentRepository;
import com.agentforge.controlplane.repo.ModelConfigRepository;
import com.agentforge.controlplane.repo.VectorStoreRepository;
import com.agentforge.controlplane.web.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class KnowledgeIndexService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeIndexService.class);
    private static final AtomicLong CHUNK_IDS = new AtomicLong(System.currentTimeMillis() * 1000);

    private final KnowledgeBaseRepository bases;
    private final KnowledgeDocumentRepository documents;
    private final ModelConfigRepository models;
    private final VectorStoreRepository vectorStores;
    private final DocumentExtractor extractor = new DocumentExtractor();
    private final DocumentCleaner cleaner = new DocumentCleaner();
    private final ChunkStrategySelector selector = new ChunkStrategySelector();
    private final EmbeddingClient embeddings;
    private final MilvusVectorStore milvus;
    private final AppSettings settings;
    private final TransactionTemplate tx;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "knowledge-index");
        thread.setDaemon(true);
        return thread;
    });

    public KnowledgeIndexService(KnowledgeBaseRepository bases, KnowledgeDocumentRepository documents,
                                 ModelConfigRepository models, VectorStoreRepository vectorStores,
                                 EmbeddingClient embeddings, MilvusVectorStore milvus, AppSettings settings,
                                 PlatformTransactionManager transactions) {
        this.bases = bases;
        this.documents = documents;
        this.models = models;
        this.vectorStores = vectorStores;
        this.embeddings = embeddings;
        this.milvus = milvus;
        this.settings = settings;
        this.tx = new TransactionTemplate(transactions);
    }

    public void enqueue(Long documentId) {
        executor.submit(() -> {
            try {
                index(documentId, false);
            } catch (Exception e) {
                log.warn("知识库文档 {} 索引失败：{}", documentId, e.getMessage());
            }
        });
    }

    public void enqueueAll(Long knowledgeId, boolean force) {
        for (KnowledgeDocument document : documents.findByKnowledgeIdOrderByIdDesc(knowledgeId)) {
            long id = document.getId();
            executor.submit(() -> index(id, force));
        }
    }

    public void index(Long documentId, boolean force) {
        List<Long> follow = tx.execute(status -> runIndex(documentId, force));
        if (follow == null) {
            return;
        }
        for (Long id : follow) {
            enqueue(id);
        }
    }

    private List<Long> runIndex(Long documentId, boolean force) {
        KnowledgeDocument document = documents.findById(documentId).orElse(null);
            if (document == null) {
                return List.of();
            }
            KnowledgeBase base = bases.findById(document.getKnowledgeId()).orElse(null);
            if (base == null) {
                fail(document, "知识库不存在");
                return List.of();
            }
        document.setStatus("processing");
        document.setErrorMessage("");
        documents.save(document);
        try {
            byte[] bytes = Files.readAllBytes(resolve(document.getStoragePath()));
            String extracted = extractor.extract(document.getFilename(), bytes);
            DocumentCleaner.CleanResult cleaned = cleaner.clean(extracted, document.getMediaType());
            if (cleaned.text().strip().length() < 20) {
                fail(document, "几乎没有可检索文本，请换成可复制文字的文件");
                return List.of();
            }
            String override = document.getStrategyOverride();
            ChunkStrategySelector.Result chunks = selector.select(cleaned.text(), override);
            if (chunks.chunks().isEmpty()) {
                fail(document, "清洗后没有可写入的分块");
                return List.of();
            }
            String hash = sha256(cleaned.text());
            ModelConfig model = models.findById(base.getEmbeddingModelId()).orElse(null);
            if (model == null || !model.isEnabled() || !"embedding".equals(model.getPurpose())) {
                fail(document, "向量模型不存在或不是 embedding 用途");
                return List.of();
            }
            VectorStore store = vectorStores.findById(base.getVectorStoreId()).orElse(null);
            if (store == null || !store.isEnabled() || !"milvus".equalsIgnoreCase(store.getType())) {
                fail(document, "向量数据库不存在、已停用或类型不是 milvus");
                return List.of();
            }
            boolean same = !force
                    && hash.equals(document.getContentHash())
                    && model.getModelId().equals(document.getEmbeddingModel())
                    && chunks.strategy().equals(document.getStrategy())
                    && store.getId().equals(document.getIndexedVectorStoreId())
                    && document.getGeneration() > 0;
            document.setCleanSummary(cleaned.summary());
            document.setProfile(chunks.profile());
            document.setStrategy(chunks.strategy());
            document.setStrategyReason(chunks.reason());
            if (same) {
                document.setStatus("ready");
                documents.save(document);
                return List.of();
            }
            List<String> texts = chunks.chunks().stream().map(ChunkStrategySelector.Chunk::content).toList();
            List<float[]> vectors = embedAll(model, texts);
            int dimension = vectors.get(0).length;
            if (base.getEmbeddingDimension() != null && base.getEmbeddingDimension() != dimension) {
                fail(document, "向量维度 " + dimension + " 与知识库已有维度 " + base.getEmbeddingDimension() + " 不一致");
                return List.of();
            }
            long generation = document.getGeneration() + 1;
            long tenantId = base.getTenantId() == null ? 1L : base.getTenantId();
            boolean rebuilt = milvus.ensureCollection(store, tenantId, base.getId(), dimension);
            List<String> contexts = ChunkStrategySelector.sectionContexts(chunks.chunks());
            List<MilvusVectorStore.ChunkRow> rows = new ArrayList<>();
            for (int i = 0; i < chunks.chunks().size(); i++) {
                ChunkStrategySelector.Chunk chunk = chunks.chunks().get(i);
                rows.add(new MilvusVectorStore.ChunkRow(
                        CHUNK_IDS.incrementAndGet(),
                        document.getId(),
                        generation,
                        chunk.ordinal(),
                        chunk.heading(),
                        chunk.content(),
                        contexts.get(i),
                        vectors.get(i)));
            }
            milvus.insert(store, tenantId, base.getId(), rows);
            milvus.deleteOlderGeneration(store, tenantId, base.getId(), document.getId(), generation);
            if (base.getEmbeddingDimension() == null) {
                base.setEmbeddingDimension(dimension);
                bases.save(base);
            }
            document.setGeneration(generation);
            document.setChunkCount(rows.size());
            document.setContentHash(hash);
            document.setEmbeddingModel(model.getModelId());
            document.setIndexedVectorStoreId(store.getId());
            document.setStatus("ready");
            document.setErrorMessage("");
            documents.save(document);
            if (!rebuilt) {
                return List.of();
            }
            List<Long> follow = new ArrayList<>();
            for (KnowledgeDocument other : documents.findByKnowledgeIdOrderByIdDesc(base.getId())) {
                if (other.getId().equals(document.getId()) || !"ready".equals(other.getStatus())) {
                    continue;
                }
                other.setContentHash("");
                documents.save(other);
                follow.add(other.getId());
            }
            return follow;
        } catch (RagCallException e) {
            fail(document, e.getMessage());
        } catch (Exception e) {
            fail(document, e.getMessage() == null ? "索引失败" : e.getMessage());
        }
        return List.of();
    }

    public Path filesRoot() {
        String configured = settings.getRagFilesDir() == null ? "" : settings.getRagFilesDir().strip();
        if (!configured.isEmpty()) {
            return Path.of(configured).toAbsolutePath().normalize();
        }
        return Path.of(System.getProperty("user.dir", ".")).resolve("rag-files").toAbsolutePath().normalize();
    }

    public Path resolve(String storagePath) {
        Path root = filesRoot();
        Path path = root.resolve(storagePath == null ? "" : storagePath).normalize();
        if (!path.startsWith(root)) {
            throw ApiException.badRequest("文档路径不合法");
        }
        return path;
    }

    private List<float[]> embedAll(ModelConfig model, List<String> texts) {
        List<float[]> all = new ArrayList<>();
        for (int i = 0; i < texts.size(); i += 16) {
            all.addAll(embeddings.embed(model, texts.subList(i, Math.min(texts.size(), i + 16))));
        }
        if (all.size() != texts.size()) {
            throw new RagCallException("向量数量与分块数量不一致", false);
        }
        return all;
    }

    private void fail(KnowledgeDocument document, String message) {
        document.setStatus("failed");
        document.setErrorMessage(message == null ? "索引失败" : message);
        documents.save(document);
    }

    private static String sha256(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            return "";
        }
    }
}

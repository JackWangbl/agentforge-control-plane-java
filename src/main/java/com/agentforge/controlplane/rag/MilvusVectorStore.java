package com.agentforge.controlplane.rag;

import com.agentforge.controlplane.domain.VectorStore;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import io.milvus.v2.client.ConnectConfig;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.common.DataType;
import io.milvus.v2.common.IndexParam;
import io.milvus.v2.service.collection.request.AddFieldReq;
import io.milvus.v2.service.collection.request.CreateCollectionReq;
import io.milvus.v2.service.collection.request.DropCollectionReq;
import io.milvus.v2.service.collection.request.HasCollectionReq;
import io.milvus.v2.service.vector.request.AnnSearchReq;
import io.milvus.v2.service.vector.request.DeleteReq;
import io.milvus.v2.service.vector.request.HybridSearchReq;
import io.milvus.v2.service.vector.request.InsertReq;
import io.milvus.v2.service.vector.request.QueryReq;
import io.milvus.v2.service.vector.request.data.EmbeddedText;
import io.milvus.v2.service.vector.request.data.FloatVec;
import io.milvus.v2.service.vector.request.ranker.RRFRanker;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class MilvusVectorStore {

    private static final Gson GSON = new Gson();

    public void probe(VectorStore store) {
        try (HeldClient held = hold(store)) {
            MilvusClientV2 client = held.client;
            client.listDatabases();
        } catch (RagCallException e) {
            throw e;
        } catch (Exception e) {
            throw new RagCallException("连接 Milvus 失败：" + e.getMessage(), true);
        }
    }

    public void dropCollection(VectorStore store, long tenantId, long knowledgeId) {
        String name = collection(tenantId, knowledgeId);
        try (HeldClient held = hold(store)) {
            MilvusClientV2 client = held.client;
            if (client.hasCollection(HasCollectionReq.builder().collectionName(name).build())) {
                client.dropCollection(DropCollectionReq.builder().collectionName(name).build());
            }
        } catch (Exception e) {
            throw new RagCallException("删除 Milvus collection 失败：" + e.getMessage(), true);
        }
    }

    /** @return true 表示旧 collection 没有上下文字段，已经删掉重建 */
    public boolean ensureCollection(VectorStore store, long tenantId, long knowledgeId, int dimension) {
        String name = collection(tenantId, knowledgeId);
        try (HeldClient held = hold(store)) {
            MilvusClientV2 client = held.client;
            boolean rebuilt = false;
            if (client.hasCollection(HasCollectionReq.builder().collectionName(name).build())) {
                if (hasContextField(client, name)) {
                    return false;
                }
                client.dropCollection(DropCollectionReq.builder().collectionName(name).build());
                rebuilt = true;
            }
            CreateCollectionReq.CollectionSchema schema = client.createSchema();
            schema.addField(AddFieldReq.builder().fieldName("chunk_id").dataType(DataType.Int64).isPrimaryKey(true).autoID(false).build());
            schema.addField(AddFieldReq.builder().fieldName("document_id").dataType(DataType.Int64).build());
            schema.addField(AddFieldReq.builder().fieldName("knowledge_id").dataType(DataType.Int64).build());
            schema.addField(AddFieldReq.builder().fieldName("tenant_id").dataType(DataType.Int64).build());
            schema.addField(AddFieldReq.builder().fieldName("generation").dataType(DataType.Int64).build());
            schema.addField(AddFieldReq.builder().fieldName("ordinal").dataType(DataType.Int64).build());
            schema.addField(AddFieldReq.builder().fieldName("heading").dataType(DataType.VarChar).maxLength(512).build());
            schema.addField(AddFieldReq.builder().fieldName("context").dataType(DataType.VarChar).maxLength(8192).build());
            schema.addField(AddFieldReq.builder()
                    .fieldName("content")
                    .dataType(DataType.VarChar)
                    .maxLength(8192)
                    .enableAnalyzer(true)
                    .analyzerParams(Map.of("type", "chinese"))
                    .build());
            schema.addField(AddFieldReq.builder().fieldName("dense").dataType(DataType.FloatVector).dimension(dimension).build());
            schema.addField(AddFieldReq.builder().fieldName("sparse").dataType(DataType.SparseFloatVector).build());
            schema.addFunction(CreateCollectionReq.Function.builder()
                    .name("content_bm25")
                    .functionType(io.milvus.common.clientenum.FunctionType.BM25)
                    .inputFieldNames(List.of("content"))
                    .outputFieldNames(List.of("sparse"))
                    .build());
            IndexParam dense = IndexParam.builder()
                    .fieldName("dense")
                    .indexType(IndexParam.IndexType.HNSW)
                    .metricType(IndexParam.MetricType.COSINE)
                    .extraParams(Map.of("M", 16, "efConstruction", 64))
                    .build();
            IndexParam sparse = IndexParam.builder()
                    .fieldName("sparse")
                    .indexType(IndexParam.IndexType.SPARSE_INVERTED_INDEX)
                    .metricType(IndexParam.MetricType.BM25)
                    .build();
            client.createCollection(CreateCollectionReq.builder()
                    .collectionName(name)
                    .collectionSchema(schema)
                    .indexParams(List.of(dense, sparse))
                    .build());
            return rebuilt;
        } catch (RagCallException e) {
            throw e;
        } catch (Exception e) {
            throw new RagCallException("创建 Milvus collection 失败：" + e.getMessage(), true);
        }
    }

    public void insert(VectorStore store, long tenantId, long knowledgeId, List<ChunkRow> rows) {
        if (rows.isEmpty()) {
            return;
        }
        String name = collection(tenantId, knowledgeId);
        List<JsonObject> data = new ArrayList<>();
        for (ChunkRow row : rows) {
            JsonObject item = new JsonObject();
            item.addProperty("chunk_id", row.chunkId());
            item.addProperty("document_id", row.documentId());
            item.addProperty("knowledge_id", knowledgeId);
            item.addProperty("tenant_id", tenantId);
            item.addProperty("generation", row.generation());
            item.addProperty("ordinal", row.ordinal());
            item.addProperty("heading", clip(row.heading(), 512));
            item.addProperty("context", clip(row.context(), 2000));
            item.addProperty("content", clip(row.content(), 8192));
            item.add("dense", GSON.toJsonTree(row.dense()));
            data.add(item);
        }
        try (HeldClient held = hold(store)) {
            MilvusClientV2 client = held.client;
            client.insert(InsertReq.builder().collectionName(name).data(data).build());
        } catch (Exception e) {
            throw new RagCallException("写入 Milvus 失败：" + e.getMessage(), true);
        }
    }

    public void deleteDocument(VectorStore store, long tenantId, long knowledgeId, long documentId) {
        deleteWhere(store, tenantId, knowledgeId, "document_id == " + documentId);
    }

    public void deleteOlderGeneration(VectorStore store, long tenantId, long knowledgeId, long documentId, long generation) {
        deleteWhere(store, tenantId, knowledgeId,
                "document_id == " + documentId + " && generation < " + generation);
    }

    public List<Map<String, Object>> preview(VectorStore store, long tenantId, long knowledgeId,
                                             long documentId, long generation, int limit) {
        String filter = "tenant_id == " + tenantId
                + " && knowledge_id == " + knowledgeId
                + " && document_id == " + documentId
                + " && generation == " + generation;
        try (HeldClient held = hold(store)) {
            MilvusClientV2 client = held.client;
            var response = client.query(QueryReq.builder()
                    .collectionName(collection(tenantId, knowledgeId))
                    .filter(filter)
                    .outputFields(outputFields(client, collection(tenantId, knowledgeId)))
                    .limit(Math.max(limit, 1))
                    .build());
            List<Map<String, Object>> rows = new ArrayList<>();
            for (var result : response.getQueryResults()) {
                Map<String, Object> entity = result.getEntity();
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("ordinal", number(entity.get("ordinal")));
                row.put("heading", String.valueOf(entity.getOrDefault("heading", "")));
                row.put("content", String.valueOf(entity.getOrDefault("content", "")));
                Object context = entity.get("context");
                row.put("context", context == null || String.valueOf(context).isBlank()
                        ? String.valueOf(entity.getOrDefault("content", "")) : String.valueOf(context));
                rows.add(row);
            }
            rows.sort((a, b) -> Integer.compare(((Number) a.get("ordinal")).intValue(), ((Number) b.get("ordinal")).intValue()));
            if (rows.size() > limit) {
                return rows.subList(0, limit);
            }
            return rows;
        } catch (Exception e) {
            throw new RagCallException("读取 Milvus 分块失败：" + e.getMessage(), true);
        }
    }

    public List<Hit> hybridSearch(VectorStore store, long tenantId, long knowledgeId, String filter,
                                  float[] dense, String query, int candidateK) {
        String name = collection(tenantId, knowledgeId);
        AnnSearchReq denseReq = AnnSearchReq.builder()
                .vectorFieldName("dense")
                .vectors(List.of(new FloatVec(toList(dense))))
                .metricType(IndexParam.MetricType.COSINE)
                .topK(candidateK)
                .expr(filter)
                .build();
        AnnSearchReq sparseReq = AnnSearchReq.builder()
                .vectorFieldName("sparse")
                .vectors(List.of(new EmbeddedText(query)))
                .metricType(IndexParam.MetricType.BM25)
                .topK(candidateK)
                .expr(filter)
                .build();
        try (HeldClient held = hold(store)) {
            MilvusClientV2 client = held.client;
            var response = client.hybridSearch(HybridSearchReq.builder()
                    .collectionName(name)
                    .searchRequests(List.of(denseReq, sparseReq))
                    .ranker(new RRFRanker(60))
                    .topK(candidateK)
                    .outFields(outputFields(client, name))
                    .build());
            List<Hit> hits = new ArrayList<>();
            if (response.getSearchResults().isEmpty()) {
                return hits;
            }
            int rank = 1;
            for (var result : response.getSearchResults().get(0)) {
                Map<String, Object> entity = result.getEntity();
                hits.add(new Hit(
                        number(entity.get("document_id")).longValue(),
                        number(entity.get("ordinal")).intValue(),
                        String.valueOf(entity.getOrDefault("heading", "")),
                        String.valueOf(entity.getOrDefault("content", "")),
                        contextOf(entity),
                        rank,
                        result.getScore()));
                rank++;
            }
            return hits;
        } catch (Exception e) {
            throw new RagCallException("Milvus 混合检索失败：" + e.getMessage(), true);
        }
    }

    private static boolean hasContextField(MilvusClientV2 client, String name) {
        var described = client.describeCollection(io.milvus.v2.service.collection.request.DescribeCollectionReq.builder()
                .collectionName(name).build());
        List<String> fields = described.getFieldNames();
        return fields != null && fields.contains("context");
    }

    private static List<String> outputFields(MilvusClientV2 client, String name) {
        List<String> fields = new ArrayList<>(List.of("document_id", "ordinal", "heading", "content", "generation"));
        if (hasContextField(client, name)) {
            fields.add("context");
        }
        return fields;
    }

    private static String contextOf(Map<String, Object> entity) {
        Object context = entity.get("context");
        if (context == null || String.valueOf(context).isBlank()) {
            return String.valueOf(entity.getOrDefault("content", ""));
        }
        return String.valueOf(context);
    }

    public static String collection(long tenantId, long knowledgeId) {
        return "kb_" + tenantId + "_" + knowledgeId;
    }

    private void deleteWhere(VectorStore store, long tenantId, long knowledgeId, String filter) {
        String name = collection(tenantId, knowledgeId);
        try (HeldClient held = hold(store)) {
            MilvusClientV2 client = held.client;
            if (!client.hasCollection(HasCollectionReq.builder().collectionName(name).build())) {
                return;
            }
            client.delete(DeleteReq.builder().collectionName(name).filter(filter).build());
        } catch (Exception e) {
            throw new RagCallException("删除 Milvus 分块失败：" + e.getMessage(), true);
        }
    }

    private static HeldClient hold(VectorStore store) {
        return new HeldClient(open(store));
    }

    private record HeldClient(MilvusClientV2 client) implements AutoCloseable {
        @Override
        public void close() {
            try {
                client.close();
            } catch (Exception ignored) {
            }
        }
    }

    private static MilvusClientV2 open(VectorStore store) {
        if (!store.isEnabled()) {
            throw new RagCallException("向量数据库已停用", true);
        }
        if (!"milvus".equalsIgnoreCase(store.getType())) {
            throw new RagCallException("向量数据库类型尚未实现：" + store.getType(), false);
        }
        ConnectConfig.ConnectConfigBuilder builder = ConnectConfig.builder()
                .uri(store.getUri().strip())
                .dbName(store.getDatabaseName());
        if (!store.getToken().isBlank()) {
            builder.token(store.getToken());
        }
        try {
            return new MilvusClientV2(builder.build());
        } catch (Exception e) {
            throw new RagCallException("连接 Milvus 失败：" + e.getMessage(), true);
        }
    }

    private static List<Float> toList(float[] dense) {
        List<Float> values = new ArrayList<>(dense.length);
        for (float value : dense) {
            values.add(value);
        }
        return values;
    }

    private static String clip(String value, int max) {
        String text = value == null ? "" : value;
        return text.length() <= max ? text : text.substring(0, max);
    }

    private static Number number(Object value) {
        if (value instanceof Number number) {
            return number;
        }
        return Long.parseLong(String.valueOf(value));
    }

    public record ChunkRow(long chunkId, long documentId, long generation, int ordinal, String heading,
                           String content, String context, float[] dense) {}

    public record Hit(long documentId, int ordinal, String heading, String content, String context, int rank, float score) {}
}

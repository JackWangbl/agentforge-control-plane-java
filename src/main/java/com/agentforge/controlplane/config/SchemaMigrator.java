package com.agentforge.controlplane.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.Statement;

/** 启动时补齐共用库里还没有的表和列。必须赶在 SeedRunner 之前跑。 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class SchemaMigrator implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SchemaMigrator.class);

    private final DataSource dataSource;

    public SchemaMigrator(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            if (!hasTable(conn, "visitor_days")) {
                try (Statement stmt = conn.createStatement()) {
                    stmt.execute("""
                            CREATE TABLE visitor_days (
                              id BIGINT NOT NULL AUTO_INCREMENT,
                              visitor_key VARCHAR(120) NOT NULL,
                              visited_on DATE NOT NULL,
                              created_at DATETIME(6) NOT NULL,
                              PRIMARY KEY (id),
                              KEY idx_visitor_days_day (visited_on)
                            )
                            """);
                }
                log.info("已创建 visitor_days 表");
            } else if (hasIndex(conn, "visitor_days", "uk_visitor_days_key_day")) {
                try (Statement stmt = conn.createStatement()) {
                    stmt.execute("ALTER TABLE visitor_days DROP INDEX uk_visitor_days_key_day");
                    stmt.execute("ALTER TABLE visitor_days ADD INDEX idx_visitor_days_day (visited_on)");
                }
                log.info("visitor_days 改为按次累计，不再按人去重");
            }
            if (!hasTable(conn, "memory_items")) {
                try (Statement stmt = conn.createStatement()) {
                    stmt.execute("""
                            CREATE TABLE memory_items (
                              id BIGINT NOT NULL AUTO_INCREMENT,
                              tenant_id BIGINT NOT NULL,
                              subject_key VARCHAR(120) NOT NULL,
                              agent_id BIGINT NULL,
                              content VARCHAR(500) NOT NULL,
                              kind VARCHAR(24) NOT NULL,
                              source VARCHAR(16) NOT NULL,
                              pinned TINYINT(1) NOT NULL DEFAULT 0,
                              session_id VARCHAR(80) NULL,
                              created_at DATETIME(6) NOT NULL,
                              updated_at DATETIME(6) NOT NULL,
                              deleted_at DATETIME(6) NULL,
                              PRIMARY KEY (id),
                              KEY idx_memory_items_owner (tenant_id, subject_key, deleted_at)
                            )
                            """);
                }
                log.info("已创建 memory_items 表");
            }
            if (!hasColumn(conn, "conversations", "subject_key")) {
                try (Statement stmt = conn.createStatement()) {
                    stmt.execute("ALTER TABLE conversations ADD COLUMN subject_key VARCHAR(120) NULL");
                }
                log.info("已为 conversations 表补齐 subject_key 列");
            }
            if (!hasTable(conn, "http_agents")) {
                try (Statement stmt = conn.createStatement()) {
                    stmt.execute("""
                            CREATE TABLE http_agents (
                              id BIGINT NOT NULL AUTO_INCREMENT,
                              tenant_id BIGINT NOT NULL DEFAULT 1,
                              owner_id BIGINT NULL,
                              name VARCHAR(100) NOT NULL,
                              description VARCHAR(300) NOT NULL DEFAULT '',
                              protocol VARCHAR(40) NOT NULL DEFAULT 'generic',
                              endpoint VARCHAR(500) NOT NULL,
                              headers JSON NULL,
                              input_field VARCHAR(80) NOT NULL DEFAULT '',
                              output_path VARCHAR(120) NOT NULL DEFAULT '',
                              timeout_seconds INT NOT NULL DEFAULT 30,
                              enabled TINYINT(1) NOT NULL DEFAULT 1,
                              config JSON NULL,
                              created_at DATETIME(6) NOT NULL,
                              updated_at DATETIME(6) NOT NULL,
                              PRIMARY KEY (id),
                              UNIQUE KEY uk_http_agents_name (name)
                            )
                            """);
                }
                log.info("已创建 http_agents 表");
            }
            if (!hasColumn(conn, "agents", "http_agent_ids")) {
                try (Statement stmt = conn.createStatement()) {
                    stmt.execute("ALTER TABLE agents ADD COLUMN http_agent_ids JSON NULL");
                }
                log.info("已为 agents 表补齐 http_agent_ids 列");
            }
            if (!hasColumn(conn, "agents", "knowledge_ids")) {
                try (Statement stmt = conn.createStatement()) {
                    stmt.execute("ALTER TABLE agents ADD COLUMN knowledge_ids JSON NULL");
                }
                log.info("已为 agents 表补齐 knowledge_ids 列");
            }
            if (!hasColumn(conn, "auth_tokens", "trial_key")) {
                try (Statement stmt = conn.createStatement()) {
                    stmt.execute("""
                            ALTER TABLE auth_tokens
                              ADD COLUMN trial_key VARCHAR(80) NULL,
                              ADD UNIQUE KEY uk_auth_tokens_trial_key (trial_key)
                            """);
                }
                log.info("已为 auth_tokens 表补齐 trial_key 列");
            }
            if (!hasColumn(conn, "model_configs", "purpose")) {
                try (Statement stmt = conn.createStatement()) {
                    stmt.execute("ALTER TABLE model_configs ADD COLUMN purpose VARCHAR(20) NOT NULL DEFAULT 'chat'");
                }
                log.info("已为 model_configs 表补齐 purpose 列");
            }
            createKnowledgeTables(conn);
        }
    }

    private void createKnowledgeTables(Connection conn) throws Exception {
        if (!hasTable(conn, "vector_stores")) {
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("""
                        CREATE TABLE vector_stores (
                          id BIGINT NOT NULL AUTO_INCREMENT,
                          tenant_id BIGINT NOT NULL DEFAULT 1,
                          owner_id BIGINT NULL,
                          name VARCHAR(100) NOT NULL,
                          type VARCHAR(40) NOT NULL DEFAULT 'milvus',
                          uri VARCHAR(500) NOT NULL,
                          database_name VARCHAR(120) NOT NULL DEFAULT 'default',
                          token TEXT NULL,
                          enabled TINYINT(1) NOT NULL DEFAULT 1,
                          is_default TINYINT(1) NOT NULL DEFAULT 0,
                          created_at DATETIME(6) NOT NULL,
                          updated_at DATETIME(6) NOT NULL,
                          PRIMARY KEY (id),
                          UNIQUE KEY uk_vector_stores_tenant_name (tenant_id, name)
                        )
                        """);
            }
            log.info("已创建 vector_stores 表");
        }
        if (!hasTable(conn, "rerank_stores")) {
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("""
                        CREATE TABLE rerank_stores (
                          id BIGINT NOT NULL AUTO_INCREMENT,
                          tenant_id BIGINT NOT NULL DEFAULT 1,
                          owner_id BIGINT NULL,
                          name VARCHAR(100) NOT NULL,
                          type VARCHAR(40) NOT NULL DEFAULT 'http',
                          base_url VARCHAR(500) NOT NULL,
                          model_id VARCHAR(160) NOT NULL DEFAULT '',
                          api_key TEXT NULL,
                          enabled TINYINT(1) NOT NULL DEFAULT 1,
                          created_at DATETIME(6) NOT NULL,
                          updated_at DATETIME(6) NOT NULL,
                          PRIMARY KEY (id),
                          UNIQUE KEY uk_rerank_stores_tenant_name (tenant_id, name)
                        )
                        """);
            }
            log.info("已创建 rerank_stores 表");
        }
        if (!hasTable(conn, "knowledge_bases")) {
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("""
                        CREATE TABLE knowledge_bases (
                          id BIGINT NOT NULL AUTO_INCREMENT,
                          tenant_id BIGINT NOT NULL DEFAULT 1,
                          owner_id BIGINT NULL,
                          name VARCHAR(100) NOT NULL,
                          description VARCHAR(300) NOT NULL DEFAULT '',
                          embedding_model_id BIGINT NOT NULL,
                          vector_store_id BIGINT NOT NULL,
                          rerank_store_id BIGINT NULL,
                          rerank_model_id BIGINT NULL,
                          visibility VARCHAR(20) NOT NULL DEFAULT 'private',
                          embedding_dimension INT NULL,
                          enabled TINYINT(1) NOT NULL DEFAULT 1,
                          top_k INT NOT NULL DEFAULT 5,
                          candidate_k INT NOT NULL DEFAULT 20,
                          score_threshold DOUBLE NOT NULL DEFAULT 0.3,
                          created_at DATETIME(6) NOT NULL,
                          updated_at DATETIME(6) NOT NULL,
                          PRIMARY KEY (id),
                          UNIQUE KEY uk_knowledge_bases_tenant_name (tenant_id, name)
                        )
                        """);
            }
            log.info("已创建 knowledge_bases 表");
        }
        if (!hasTable(conn, "knowledge_documents")) {
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("""
                        CREATE TABLE knowledge_documents (
                          id BIGINT NOT NULL AUTO_INCREMENT,
                          tenant_id BIGINT NOT NULL DEFAULT 1,
                          owner_id BIGINT NULL,
                          knowledge_id BIGINT NOT NULL,
                          filename VARCHAR(255) NOT NULL,
                          media_type VARCHAR(120) NOT NULL DEFAULT '',
                          byte_size BIGINT NOT NULL DEFAULT 0,
                          storage_path VARCHAR(500) NOT NULL DEFAULT '',
                          status VARCHAR(24) NOT NULL DEFAULT 'queued',
                          error_message TEXT NULL,
                          clean_summary JSON NULL,
                          profile JSON NULL,
                          strategy VARCHAR(40) NOT NULL DEFAULT '',
                          strategy_reason VARCHAR(500) NOT NULL DEFAULT '',
                          strategy_override VARCHAR(40) NOT NULL DEFAULT '',
                          chunk_count INT NOT NULL DEFAULT 0,
                          generation BIGINT NOT NULL DEFAULT 0,
                          content_hash VARCHAR(64) NOT NULL DEFAULT '',
                          embedding_model VARCHAR(160) NOT NULL DEFAULT '',
                          indexed_vector_store_id BIGINT NULL,
                          created_at DATETIME(6) NOT NULL,
                          updated_at DATETIME(6) NOT NULL,
                          PRIMARY KEY (id),
                          KEY idx_knowledge_documents_kb (knowledge_id, status)
                        )
                        """);
            }
            log.info("已创建 knowledge_documents 表");
        }
        if (!hasColumn(conn, "vector_stores", "is_default")) {
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("ALTER TABLE vector_stores ADD COLUMN is_default TINYINT(1) NOT NULL DEFAULT 0");
            }
            log.info("已为 vector_stores 表补齐 is_default 列");
        }
        if (!hasColumn(conn, "knowledge_bases", "visibility")) {
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("ALTER TABLE knowledge_bases ADD COLUMN visibility VARCHAR(20) NOT NULL DEFAULT 'tenant'");
            }
            log.info("已为 knowledge_bases 表补齐 visibility 列，已有库保持租户内可见");
        }
        if (!hasColumn(conn, "knowledge_bases", "rerank_model_id")) {
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("ALTER TABLE knowledge_bases ADD COLUMN rerank_model_id BIGINT NULL");
            }
            log.info("已为 knowledge_bases 表补齐 rerank_model_id 列");
        }
        if (!hasTable(conn, "knowledge_members")) {
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("""
                        CREATE TABLE knowledge_members (
                          id BIGINT NOT NULL AUTO_INCREMENT,
                          knowledge_id BIGINT NOT NULL,
                          user_id BIGINT NOT NULL,
                          role VARCHAR(20) NOT NULL,
                          created_at DATETIME(6) NOT NULL,
                          updated_at DATETIME(6) NOT NULL,
                          PRIMARY KEY (id),
                          UNIQUE KEY uk_knowledge_members (knowledge_id, user_id)
                        )
                        """);
            }
            log.info("已创建 knowledge_members 表");
        }
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("""
                    INSERT INTO knowledge_members (knowledge_id, user_id, role, created_at, updated_at)
                    SELECT kb.id, kb.owner_id, 'owner', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)
                    FROM knowledge_bases kb
                    WHERE kb.owner_id IS NOT NULL
                      AND NOT EXISTS (
                        SELECT 1 FROM knowledge_members m
                        WHERE m.knowledge_id = kb.id AND m.user_id = kb.owner_id
                      )
                    """);
            stmt.execute("""
                    UPDATE vector_stores v
                    JOIN (
                      SELECT tenant_id, MIN(id) AS id
                      FROM vector_stores
                      WHERE enabled = 1
                      GROUP BY tenant_id
                    ) picked ON v.id = picked.id
                    SET v.is_default = 1
                    WHERE NOT EXISTS (
                      SELECT 1 FROM (SELECT tenant_id FROM vector_stores WHERE is_default = 1) defaults
                      WHERE defaults.tenant_id = v.tenant_id
                    )
                    """);
        }
        backfillRerankModels(conn);
    }

    private void backfillRerankModels(Connection conn) throws Exception {
        if (!hasColumn(conn, "knowledge_bases", "rerank_store_id") || !hasTable(conn, "rerank_stores")) {
            return;
        }
        try (Statement stmt = conn.createStatement();
             ResultSet rows = stmt.executeQuery("""
                     SELECT kb.id AS kb_id, rs.id AS store_id, rs.tenant_id, rs.owner_id, rs.name,
                            rs.base_url, rs.model_id, rs.api_key
                     FROM knowledge_bases kb
                     JOIN rerank_stores rs ON rs.id = kb.rerank_store_id
                     WHERE kb.rerank_model_id IS NULL
                     """)) {
            while (rows.next()) {
                long kbId = rows.getLong("kb_id");
                long storeId = rows.getLong("store_id");
                String name = "重排序·" + rows.getString("name");
                if (modelNameTaken(conn, name)) {
                    name = name + "·" + storeId;
                }
                try (var insert = conn.prepareStatement("""
                        INSERT INTO model_configs
                          (tenant_id, owner_id, name, provider, model_id, base_url, api_key_ref, api_key,
                           temperature, enabled, purpose, created_at, updated_at)
                        VALUES (?, ?, ?, 'HTTP', ?, ?, '', ?, 0, 1, 'rerank', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                        """, Statement.RETURN_GENERATED_KEYS)) {
                    insert.setLong(1, rows.getLong("tenant_id"));
                    long owner = rows.getLong("owner_id");
                    if (rows.wasNull()) {
                        insert.setNull(2, java.sql.Types.BIGINT);
                    } else {
                        insert.setLong(2, owner);
                    }
                    insert.setString(3, name);
                    insert.setString(4, rows.getString("model_id") == null ? "" : rows.getString("model_id"));
                    insert.setString(5, rows.getString("base_url") == null ? "" : rows.getString("base_url"));
                    insert.setString(6, rows.getString("api_key") == null ? "" : rows.getString("api_key"));
                    insert.executeUpdate();
                    try (ResultSet keys = insert.getGeneratedKeys()) {
                        if (keys.next()) {
                            try (var update = conn.prepareStatement(
                                    "UPDATE knowledge_bases SET rerank_model_id = ? WHERE id = ?")) {
                                update.setLong(1, keys.getLong(1));
                                update.setLong(2, kbId);
                                update.executeUpdate();
                            }
                        }
                    }
                }
                log.info("已把重排序服务 {} 迁成模型配置", rows.getString("name"));
            }
        }
    }

    private static boolean modelNameTaken(Connection conn, String name) throws Exception {
        try (var stmt = conn.prepareStatement("SELECT 1 FROM model_configs WHERE name = ?")) {
            stmt.setString(1, name);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next();
            }
        }
    }

    private static boolean hasTable(Connection conn, String table) throws Exception {
        DatabaseMetaData meta = conn.getMetaData();
        String catalog = conn.getCatalog();
        return tableExists(meta, catalog, table)
                || tableExists(meta, catalog, table.toUpperCase())
                || tableExists(meta, catalog, table.toLowerCase());
    }

    private static boolean tableExists(DatabaseMetaData meta, String catalog, String table) throws Exception {
        try (ResultSet rs = meta.getTables(catalog, null, table, new String[]{"TABLE"})) {
            return rs.next();
        }
    }

    private static boolean hasIndex(Connection conn, String table, String index) throws Exception {
        DatabaseMetaData meta = conn.getMetaData();
        try (ResultSet rs = meta.getIndexInfo(conn.getCatalog(), null, table, false, false)) {
            while (rs.next()) {
                if (index.equalsIgnoreCase(rs.getString("INDEX_NAME"))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean hasColumn(Connection conn, String table, String column) throws Exception {
        DatabaseMetaData meta = conn.getMetaData();
        String catalog = conn.getCatalog();
        if (columnExists(meta, catalog, table, column)
                || columnExists(meta, catalog, table.toUpperCase(), column.toUpperCase())) {
            return true;
        }
        return columnExists(meta, catalog, table.toLowerCase(), column.toLowerCase());
    }

    private static boolean columnExists(DatabaseMetaData meta, String catalog, String table, String column)
            throws Exception {
        try (ResultSet rs = meta.getColumns(catalog, null, table, column)) {
            return rs.next();
        }
    }
}

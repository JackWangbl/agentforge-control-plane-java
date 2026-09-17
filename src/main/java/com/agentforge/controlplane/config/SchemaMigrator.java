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

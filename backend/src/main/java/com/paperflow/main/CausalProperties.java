package com.paperflow.main;

import com.paperflow.causal.model.IngestionMode;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.time.Duration;
import java.util.List;

@ConfigurationProperties("paperflow.causal")
public record CausalProperties(
        boolean enabled,
        IngestionMode defaultMode,
        URI engineUrl,
        Duration requestTimeout,
        int maxConcurrency,
        int maxCalls,
        int maxBatchTargets,
        Duration cacheTtl,
        String jdbcUrl,
        String dbHost,
        String dbPort,
        String dbName,
        String dbSchema,
        String dbUser,
        String dbPassword
) {
    public CausalProperties {
        defaultMode = defaultMode == null ? IngestionMode.RAW : defaultMode;
        requestTimeout = requestTimeout == null ? Duration.ofHours(1) : requestTimeout;
        cacheTtl = cacheTtl == null ? Duration.ofHours(12) : cacheTtl;
        jdbcUrl = jdbcUrl == null ? "" : jdbcUrl.strip();
        dbHost = dbHost == null ? "" : dbHost.strip();
        dbPort = dbPort == null ? "" : dbPort.strip();
        dbName = dbName == null ? "" : dbName.strip();
        dbSchema = dbSchema == null ? "" : dbSchema.strip();
        dbUser = dbUser == null ? "" : dbUser.strip();
        dbPassword = dbPassword == null ? "" : dbPassword;
        if (engineUrl == null || engineUrl.getHost() == null
                || !List.of("http", "https").contains(engineUrl.getScheme())
                || engineUrl.getUserInfo() != null || engineUrl.getQuery() != null || engineUrl.getFragment() != null) {
            throw new IllegalArgumentException("PAPERFLOW_CAUSAL_ENGINE_URL 必须是 HTTP API 基地址");
        }
        if (requestTimeout.isNegative() || requestTimeout.isZero()) {
            throw new IllegalArgumentException("PAPERFLOW_CAUSAL_TIMEOUT 必须大于零");
        }
        if (maxConcurrency < 1 || maxConcurrency > 16) {
            throw new IllegalArgumentException("PAPERFLOW_CAUSAL_CONCURRENCY 必须在 1 到 16 之间");
        }
        if (maxCalls < 1 || maxBatchTargets < 1 || maxBatchTargets > 500) {
            throw new IllegalArgumentException("因果抽取限额配置无效");
        }
        if (!dbSchema.isBlank() && !dbSchema.matches("[a-z_][a-z0-9_]{0,62}")) {
            throw new IllegalArgumentException("CAUSAL_DB_SCHEMA 必须是 PostgreSQL 标识符");
        }
    }

    public boolean hasSeparateDatasource() {
        return !jdbcUrl.isBlank() || !dbHost.isBlank() || !dbPort.isBlank()
                || !dbName.isBlank() || !dbSchema.isBlank() || !dbUser.isBlank();
    }
}

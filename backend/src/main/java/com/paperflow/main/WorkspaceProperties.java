package com.paperflow.main;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;

@ConfigurationProperties("paperflow")
public record WorkspaceProperties(
        Path storage, Path legacyStorage, String openalexSchema, URI mineruUrl,
        String mineruTier, Duration mineruTimeout, Duration journalCacheTtl,
        String adminUsername, String adminPassword
) {
    public WorkspaceProperties {
        if (!openalexSchema.matches("[a-z_][a-z0-9_]{0,62}")) {
            throw new IllegalArgumentException("PAPERFLOW_OPENALEX_SCHEMA 必须是 PostgreSQL 标识符");
        }
        if (mineruTimeout.isNegative() || mineruTimeout.isZero()) {
            throw new IllegalArgumentException("PAPERFLOW_MINERU_TIMEOUT 必须大于零");
        }
        if (journalCacheTtl == null) {
            journalCacheTtl = Duration.ofSeconds(15);
        } else if (journalCacheTtl.isNegative()) {
            throw new IllegalArgumentException("PAPERFLOW_JOURNAL_CACHE_TTL 不能为负数");
        }
        if (mineruUrl.getHost() == null || !java.util.List.of("http", "https").contains(mineruUrl.getScheme())
                || mineruUrl.getUserInfo() != null || mineruUrl.getQuery() != null || mineruUrl.getFragment() != null) {
            throw new IllegalArgumentException("PAPERFLOW_MINERU_URL 必须是 HTTP API 基地址");
        }
        if (!mineruTier.isBlank() && !java.util.List.of("flash", "basic", "standard", "advanced").contains(mineruTier)) {
            throw new IllegalArgumentException("PAPERFLOW_MINERU_TIER 档位无效");
        }
    }
}

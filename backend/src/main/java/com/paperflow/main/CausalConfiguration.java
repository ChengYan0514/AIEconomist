package com.paperflow.main;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paperflow.adapters.causal.HttpCausalExtractor;
import com.paperflow.adapters.persistence.JdbcCausalBatchStore;
import com.paperflow.adapters.persistence.JdbcCausalFactReader;
import com.paperflow.adapters.persistence.JdbcCausalFactWriter;
import com.paperflow.adapters.persistence.JdbcCausalReadModelStore;
import com.paperflow.audit.AuditLog;
import com.paperflow.causal.extraction.*;
import com.paperflow.causal.graph.CausalFactReader;
import com.paperflow.causal.graph.CausalGraphService;
import com.paperflow.causal.graph.CausalReadModelRefresher;
import com.paperflow.causal.graph.CausalReadModelStore;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.util.concurrent.Executors;

@Configuration(proxyBeanMethods = false)
class CausalConfiguration {
    record CausalDatabase(DataSource dataSource, TransactionTemplate transactions, boolean owned) implements AutoCloseable {
        @Override
        public void close() {
            if (owned && dataSource instanceof HikariDataSource hikari) hikari.close();
        }
    }

    @Bean
    CausalDatabase causalDatabase(
            DataSource primary, TransactionTemplate primaryTx, CausalProperties causal,
            @Value("${spring.datasource.url}") String mainUrl,
            @Value("${spring.datasource.username}") String mainUser,
            @Value("${spring.datasource.password:}") String mainPass
    ) {
        if (!causal.hasSeparateDatasource()) return new CausalDatabase(primary, primaryTx, false);
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(resolveCausalUrl(causal, mainUrl));
        cfg.setUsername(causal.dbUser().isBlank() ? mainUser : causal.dbUser());
        cfg.setPassword(causal.dbPassword().isBlank() ? mainPass : causal.dbPassword());
        cfg.setMaximumPoolSize(6);
        cfg.setPoolName("causal-pool");
        var ds = new HikariDataSource(cfg);
        return new CausalDatabase(ds, new TransactionTemplate(new DataSourceTransactionManager(ds)), true);
    }

    @Bean
    CausalFactReader causalFactReader(CausalDatabase causalDb) {
        return new JdbcCausalFactReader(new JdbcTemplate(causalDb.dataSource()));
    }

    @Bean
    CausalReadModelStore causalReadModelStore(
            JdbcTemplate mainJdbc, TransactionTemplate transactions, WorkspaceProperties workspace
    ) {
        return new JdbcCausalReadModelStore(mainJdbc, transactions, workspace.openalexSchema());
    }

    @Bean
    CausalReadModelRefresher causalReadModelRefresher(
            CausalFactReader facts, CausalReadModelStore store, ObjectMapper json, CausalProperties causal
    ) {
        var executor = Executors.newSingleThreadExecutor(task -> {
            Thread t = new Thread(task, "causal-readmodel-refresher");
            t.setDaemon(true);
            return t;
        });
        return new CausalReadModelRefresher(facts, store, json, causal.cacheTtl(), executor);
    }

    @Bean
    CausalGraphService causalGraphService(
            CausalFactReader facts, CausalReadModelStore store, CausalReadModelRefresher refresher, AuditLog audit
    ) {
        return new CausalGraphService(facts, store, refresher, audit);
    }

    @Bean
    HttpCausalExtractor causalExtractor(CausalProperties causal, ObjectMapper json) {
        return new HttpCausalExtractor(causal.engineUrl(), json, causal.requestTimeout());
    }

    @Bean
    CausalBatchStore causalBatchStore(JdbcTemplate mainJdbc, ObjectMapper json) {
        return new JdbcCausalBatchStore(mainJdbc, json);
    }

    @Bean
    CausalFactWriter causalFactWriter(CausalDatabase causalDb) {
        return new JdbcCausalFactWriter(new JdbcTemplate(causalDb.dataSource()), causalDb.transactions());
    }

    @Bean
    CausalEventHub causalEventHub() {
        return new CausalEventHub();
    }

    @Bean
    CausalPayloadValidator causalPayloadValidator(ObjectMapper json) {
        return new CausalPayloadValidator(json);
    }

    @Bean
    CausalBatchRunner causalBatchRunner(
            CausalBatchStore store, CausalExtractor extractor, CausalFactWriter writer,
            CausalPayloadValidator validator, CausalReadModelRefresher readModel,
            CausalEventHub events, AuditLog audit, ObjectMapper json, CausalProperties causal
    ) {
        return new CausalBatchRunner(store, extractor, writer, validator, readModel, events, audit, json,
                causal.maxConcurrency(), causal.requestTimeout(), causal.maxCalls());
    }

    @Bean
    CausalExtractionService causalExtractionService(
            CausalBatchStore store, CausalExtractor extractor, CausalBatchRunner runner, CausalEventHub events,
            AuditLog audit, TransactionTemplate transactions, ObjectMapper json, CausalProperties causal
    ) {
        return new CausalExtractionService(store, extractor, runner, events, audit, transactions, json,
                causal.enabled(), causal.defaultMode(), causal.maxCalls(), causal.maxBatchTargets());
    }

    @Bean
    ApplicationRunner causalStartupRunner(CausalBatchRunner runner, CausalReadModelRefresher refresher) {
        return args -> {
            runner.reconcileOnStartup();
            refresher.ensureReadyAsync();
        };
    }

    private static String resolveCausalUrl(CausalProperties causal, String mainUrl) {
        if (!causal.jdbcUrl().isBlank()) return causal.jdbcUrl();
        String host = causal.dbHost().isBlank() ? "localhost" : causal.dbHost();
        String port = causal.dbPort().isBlank() ? "5432" : causal.dbPort();
        String name = causal.dbName().isBlank() ? "openalex" : causal.dbName();
        String schema = causal.dbSchema().isBlank() ? "widi_chengyan" : causal.dbSchema();
        if (causal.dbHost().isBlank() && causal.dbPort().isBlank() && causal.dbName().isBlank() && !causal.dbSchema().isBlank()) {
            return mainUrl.contains("currentSchema=")
                    ? mainUrl.replaceFirst("currentSchema=[^&]+", "currentSchema=" + schema)
                    : mainUrl + (mainUrl.contains("?") ? "&" : "?") + "currentSchema=" + schema;
        }
        return "jdbc:postgresql://" + host + ":" + port + "/" + name + "?currentSchema=" + schema;
    }
}

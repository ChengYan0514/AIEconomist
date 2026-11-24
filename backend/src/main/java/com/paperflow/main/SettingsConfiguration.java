package com.paperflow.main;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paperflow.adapters.persistence.JdbcServiceSettingsStore;
import com.paperflow.audit.AuditLog;
import com.paperflow.settings.SecretBox;
import com.paperflow.settings.ServiceConnectivityProber;
import com.paperflow.settings.ServiceSettings;
import com.paperflow.settings.ServiceSettings.*;
import com.paperflow.settings.ServiceSettingsService;
import com.paperflow.settings.ServiceSettingsStore;
import com.paperflow.settings.ThinkingStyle;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration(proxyBeanMethods = false)
class SettingsConfiguration {
    @Bean
    ServiceSettingsStore serviceSettingsStore(JdbcTemplate jdbc) {
        return new JdbcServiceSettingsStore(jdbc);
    }

    @Bean
    SecretBox secretBox(
            WorkspaceProperties workspace,
            @Value("${PAPERFLOW_SETTINGS_SECRET:}") String explicitSecret,
            @Value("${spring.datasource.password:}") String dbPassword
    ) {
        String seed = !explicitSecret.isBlank()
                ? explicitSecret
                : dbPassword + ":" + workspace.adminPassword() + ":paperflow-settings-v1";
        return new SecretBox(seed);
    }

    @Bean
    ServiceSettingsService serviceSettingsService(
            ServiceSettingsStore store, WorkspaceProperties workspace, CausalProperties causal,
            SecretBox secrets, ObjectMapper json, AuditLog audit, TransactionTemplate transactions,
            @Value("${CAUSAL_CHAT_BASE_URL:}") String chatBaseUrl,
            @Value("${CAUSAL_CHAT_MODEL:}") String chatModel,
            @Value("${CAUSAL_CHAT_API_KEY:}") String chatApiKey,
            @Value("${CAUSAL_ENABLE_THINKING:false}") boolean enableThinking,
            @Value("${CAUSAL_THINKING_STYLE:AUTO}") String thinkingStyle,
            @Value("${CAUSAL_EMBEDDING_BASE_URL:}") String embBaseUrl,
            @Value("${CAUSAL_EMBEDDING_MODEL:}") String embModel,
            @Value("${CAUSAL_EMBEDDING_API_KEY:}") String embApiKey
    ) {
        int mineruSeconds = (int) Math.max(10, Math.min(86400, workspace.mineruTimeout().toSeconds()));
        ServiceSettings defaults = new ServiceSettings(
                new MineruConfig(workspace.mineruUrl(), workspace.mineruTier(), mineruSeconds),
                new CausalConfig(causal.enabled(), causal.engineUrl(), causal.defaultMode(), causal.maxCalls()),
                new ChatModelConfig(chatBaseUrl, chatModel, chatApiKey, enableThinking,
                        ThinkingStyle.parse(thinkingStyle, ThinkingStyle.AUTO)),
                new EmbeddingModelConfig(embBaseUrl, embModel, embApiKey),
                null, null);
        return new ServiceSettingsService(store, defaults, secrets, json, audit, transactions);
    }

    @Bean
    ServiceConnectivityProber serviceConnectivityProber(ObjectMapper json) {
        return new ServiceConnectivityProber(json);
    }
}

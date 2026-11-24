package com.paperflow.settings;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paperflow.audit.AuditLog;
import com.paperflow.audit.model.AuditEvent;
import com.paperflow.audit.model.AuditFilter;
import com.paperflow.audit.model.AuditOutcome;
import com.paperflow.causal.model.IngestionMode;
import com.paperflow.settings.ServiceSettings.*;
import com.paperflow.settings.ServiceSettingsService.*;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ServiceSettingsTest {
    @Test
    void resolvesThinkingExtraBodyAcrossMainstreamProviders() {
        assertThat(ThinkingStyle.AUTO.resolveExtraBody("https://api.deepseek.com/v1", "deepseek-chat", false))
                .isEqualTo(Map.of("thinking", Map.of("type", "disabled")));
        assertThat(ThinkingStyle.AUTO.resolveExtraBody("https://api.deepseek.com/v1", "deepseek-chat", true))
                .isEqualTo(Map.of("thinking", Map.of("type", "enabled")));
        assertThat(ThinkingStyle.AUTO.resolveExtraBody("https://api.siliconflow.cn/v1", "Qwen/Qwen3-32B", false))
                .isEqualTo(Map.of("enable_thinking", false));
        assertThat(ThinkingStyle.AUTO.resolveExtraBody("https://dashscope.aliyuncs.com/compatible-mode/v1", "qwen3-max", true))
                .isEqualTo(Map.of("enable_thinking", true));
        assertThat(ThinkingStyle.AUTO.resolveExtraBody("http://127.0.0.1:8000/v1", "Qwen3-14B", false))
                .isEqualTo(Map.of("chat_template_kwargs", Map.of("enable_thinking", false)));
        assertThat(ThinkingStyle.AUTO.resolveExtraBody("http://127.0.0.1:11434/v1", "deepseek-r1:14b", false))
                .isEqualTo(Map.of("think", false));
        assertThat(ThinkingStyle.AUTO.resolveExtraBody("https://api.openai.com/v1", "o3-mini", false))
                .isEqualTo(Map.of("reasoning_effort", "none"));
        assertThat(ThinkingStyle.AUTO.resolveExtraBody("https://api.openai.com/v1", "gpt-4o", false))
                .isEmpty();
        assertThat(ThinkingStyle.NONE.resolveExtraBody("https://api.deepseek.com/v1", "deepseek-chat", false))
                .isEmpty();
    }

    @Test
    void encryptsDecryptsAndMasksSecrets() {
        SecretBox box = new SecretBox("test-seed-123");
        String encrypted = box.encrypt("sk-deepseek-1234567890");
        assertThat(encrypted).startsWith("enc:v1:").doesNotContain("1234567890");
        assertThat(box.decrypt(encrypted)).isEqualTo("sk-deepseek-1234567890");
        assertThat(SecretBox.mask("sk-deepseek-1234567890")).isEqualTo("sk-****7890");
        assertThat(SecretBox.mask("short")).isEqualTo("****");
        assertThat(SecretBox.mask("")).isEmpty();
    }

    @Test
    void updatesSettingsPreservesOrClearsApiKeysAndEncryptsAtRest() {
        InMemoryStore store = new InMemoryStore();
        ServiceSettings defaults = new ServiceSettings(
                new MineruConfig(URI.create("http://127.0.0.1:18002"), "", 3600),
                new CausalConfig(false, URI.create("http://127.0.0.1:18003"), IngestionMode.RAW, 2000),
                new ChatModelConfig("", "", "", false, ThinkingStyle.AUTO),
                new EmbeddingModelConfig("", "", ""),
                null, null);
        RecordingAudit audit = new RecordingAudit();
        ServiceSettingsService service = new ServiceSettingsService(
                store, defaults, new SecretBox("unit-test-secret"), new ObjectMapper(), audit, new NoopTx());

        // 1. Configure DeepSeek Chat + SiliconFlow Embedding + custom MinerU port
        ServiceSettings saved = service.update("admin", new UpdateCommand(
                new MineruUpdate("http://127.0.0.1:19002", "standard", 1800),
                new CausalUpdate(true, "http://127.0.0.1:19003", "STANDARDIZED", 1500),
                new ChatUpdate("https://api.deepseek.com/v1", "deepseek-chat", "sk-ds-secret9999", false, false, "AUTO"),
                new EmbeddingUpdate("https://api.siliconflow.cn/v1", "BAAI/bge-m3", "sk-sf-secret8888", false)));

        assertThat(saved.mineru().url().toString()).isEqualTo("http://127.0.0.1:19002");
        assertThat(saved.causal().enabled()).isTrue();
        assertThat(saved.chat().apiKey()).isEqualTo("sk-ds-secret9999");
        assertThat(saved.chat().enableThinking()).isFalse();
        assertThat(store.rawJson).doesNotContain("sk-ds-secret9999").doesNotContain("sk-sf-secret8888");
        assertThat(audit.summaries.toString()).doesNotContain("sk-ds-secret9999").doesNotContain("sk-sf-secret8888");

        // 2. Blank apiKey preserves existing secret when editing other fields
        ServiceSettings kept = service.update("admin", new UpdateCommand(
                null, null,
                new ChatUpdate("https://api.deepseek.com/v1", "deepseek-chat", "", false, true, "THINKING_OBJECT"),
                null));
        assertThat(kept.chat().apiKey()).isEqualTo("sk-ds-secret9999");
        assertThat(kept.chat().enableThinking()).isTrue();
        assertThat(kept.chat().thinkingStyle()).isEqualTo(ThinkingStyle.THINKING_OBJECT);

        // 3. Switching to keyless local model with clearApiKey=true removes secret
        ServiceSettings local = service.update("admin", new UpdateCommand(
                null, null,
                new ChatUpdate("http://127.0.0.1:8000/v1", "Qwen3-32B", "", true, false, "AUTO"),
                new EmbeddingUpdate("http://127.0.0.1:8001/v1", "bge-m3", "", true)));
        assertThat(local.chat().apiKey()).isEmpty();
        assertThat(local.embedding().apiKey()).isEmpty();
        assertThat(local.chat().thinkingStyle().effectiveStyle(local.chat().baseUrl(), local.chat().model()))
                .isEqualTo(ThinkingStyle.CHAT_TEMPLATE_KWARGS);

        // 4. Rejects invalid URL
        assertThatThrownBy(() -> service.update("admin", new UpdateCommand(
                new MineruUpdate("ftp://bad-host:18002", "", 3600), null, null, null)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static final class InMemoryStore implements ServiceSettingsStore {
        String rawJson;
        String updatedBy;
        Instant updatedAt;

        @Override
        public StoredSettings load() {
            return rawJson == null ? null : new StoredSettings(rawJson, updatedAt, updatedBy);
        }

        @Override
        public void save(String valueJson, String updatedBy) {
            this.rawJson = valueJson;
            this.updatedBy = updatedBy;
            this.updatedAt = Instant.now();
        }
    }

    private static final class RecordingAudit implements AuditLog {
        final List<Map<String, Object>> summaries = new ArrayList<>();

        @Override
        public void record(String actor, String action, String objectType, String objectId, AuditOutcome outcome,
                           Map<String, Object> summary) {
            summaries.add(summary);
        }

        @Override public List<AuditEvent> list(AuditFilter filter, long offset, int size) { return List.of(); }
        @Override public long count(AuditFilter filter) { return 0; }
        @Override public AuditEvent find(long eventId) { return null; }
    }

    private static final class NoopTx extends TransactionTemplate {
        @Override
        public <T> T execute(TransactionCallback<T> action) {
            return action.doInTransaction(null);
        }
    }
}

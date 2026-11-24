package com.paperflow.settings;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paperflow.audit.AuditLog;
import com.paperflow.audit.model.AuditOutcome;
import com.paperflow.causal.model.IngestionMode;
import com.paperflow.settings.ServiceSettings.*;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

public final class ServiceSettingsService {
    public record MineruUpdate(String url, String tier, Integer timeoutSeconds) {}
    public record CausalUpdate(Boolean enabled, String url, String defaultMode, Integer maxCalls) {}
    public record ChatUpdate(String baseUrl, String model, String apiKey, boolean clearApiKey,
                             Boolean enableThinking, String thinkingStyle) {}
    public record EmbeddingUpdate(String baseUrl, String model, String apiKey, boolean clearApiKey) {}
    public record UpdateCommand(MineruUpdate mineru, CausalUpdate causal, ChatUpdate chat, EmbeddingUpdate embedding) {}

    private final ServiceSettingsStore store;
    private final ServiceSettings defaults;
    private final SecretBox secrets;
    private final ObjectMapper json;
    private final AuditLog audit;
    private final TransactionTemplate transactions;
    private volatile ServiceSettings cached;

    public ServiceSettingsService(
            ServiceSettingsStore store, ServiceSettings defaults, SecretBox secrets,
            ObjectMapper json, AuditLog audit, TransactionTemplate transactions
    ) {
        this.store = store;
        this.defaults = defaults;
        this.secrets = secrets;
        this.json = json;
        this.audit = audit;
        this.transactions = transactions;
    }

    public ServiceSettings current() {
        ServiceSettings snapshot = cached;
        if (snapshot != null) return snapshot;
        synchronized (this) {
            if (cached == null) cached = loadEffective();
            return cached;
        }
    }

    public ServiceSettings update(String actor, UpdateCommand cmd) {
        if (cmd == null) throw new IllegalArgumentException("配置内容不能为空");
        ServiceSettings prev = current();
        ServiceSettings next = merge(prev, cmd);
        String payload = encodeStored(next);
        transactions.executeWithoutResult(status -> {
            store.save(payload, actor);
            audit.record(actor, "SERVICE_SETTINGS_UPDATE", "SERVICE_SETTINGS", "service_config",
                    AuditOutcome.SUCCEEDED, auditSummary(next));
        });
        synchronized (this) {
            cached = loadEffective();
            return cached;
        }
    }

    public String resolveChatApiKey(String candidate, boolean clearApiKey) {
        if (clearApiKey) return "";
        if (candidate != null && !candidate.isBlank()) return candidate.strip();
        return current().chat().apiKey();
    }

    public String resolveEmbeddingApiKey(String candidate, boolean clearApiKey) {
        if (clearApiKey) return "";
        if (candidate != null && !candidate.isBlank()) return candidate.strip();
        return current().embedding().apiKey();
    }

    private ServiceSettings loadEffective() {
        var stored = store.load();
        if (stored == null || stored.valueJson() == null || stored.valueJson().isBlank()) {
            return defaults;
        }
        try {
            JsonNode root = json.readTree(stored.valueJson());
            MineruConfig mineru = readMineru(root.path("mineru"));
            CausalConfig causal = readCausal(root.path("causal"));
            ChatModelConfig chat = readChat(root.path("chat"));
            EmbeddingModelConfig emb = readEmbedding(root.path("embedding"));
            return new ServiceSettings(mineru, causal, chat, emb, stored.updatedAt(), stored.updatedBy());
        } catch (Exception ignored) {
            return defaults;
        }
    }

    private MineruConfig readMineru(JsonNode node) {
        var d = defaults.mineru();
        URI url = text(node, "url") != null ? ServiceSettings.parseHttpUri(text(node, "url"), "MinerU 服务地址") : d.url();
        String tier = node.has("tier") ? node.path("tier").asText("") : d.tier();
        int timeout = node.hasNonNull("timeoutSeconds") ? node.path("timeoutSeconds").asInt(d.timeoutSeconds()) : d.timeoutSeconds();
        return new MineruConfig(url, tier, timeout);
    }

    private CausalConfig readCausal(JsonNode node) {
        var d = defaults.causal();
        boolean enabled = node.hasNonNull("enabled") ? node.path("enabled").asBoolean(d.enabled()) : d.enabled();
        URI url = text(node, "url") != null ? ServiceSettings.parseHttpUri(text(node, "url"), "因果抽取引擎地址") : d.url();
        IngestionMode mode = IngestionMode.parse(text(node, "defaultMode"), d.defaultMode());
        int maxCalls = node.hasNonNull("maxCalls") ? node.path("maxCalls").asInt(d.maxCalls()) : d.maxCalls();
        return new CausalConfig(enabled, url, mode, maxCalls);
    }

    private ChatModelConfig readChat(JsonNode node) {
        var d = defaults.chat();
        String baseUrl = node.has("baseUrl") ? node.path("baseUrl").asText("") : d.baseUrl();
        String model = node.has("model") ? node.path("model").asText("") : d.model();
        String apiKey = node.has("apiKeyEnc") ? secrets.decrypt(node.path("apiKeyEnc").asText("")) : d.apiKey();
        boolean thinking = node.hasNonNull("enableThinking") ? node.path("enableThinking").asBoolean(d.enableThinking()) : d.enableThinking();
        ThinkingStyle style = ThinkingStyle.parse(text(node, "thinkingStyle"), d.thinkingStyle());
        return new ChatModelConfig(baseUrl, model, apiKey, thinking, style);
    }

    private EmbeddingModelConfig readEmbedding(JsonNode node) {
        var d = defaults.embedding();
        String baseUrl = node.has("baseUrl") ? node.path("baseUrl").asText("") : d.baseUrl();
        String model = node.has("model") ? node.path("model").asText("") : d.model();
        String apiKey = node.has("apiKeyEnc") ? secrets.decrypt(node.path("apiKeyEnc").asText("")) : d.apiKey();
        return new EmbeddingModelConfig(baseUrl, model, apiKey);
    }

    private ServiceSettings merge(ServiceSettings prev, UpdateCommand cmd) {
        MineruConfig m = cmd.mineru() == null ? prev.mineru() : new MineruConfig(
                cmd.mineru().url() != null ? ServiceSettings.parseHttpUri(cmd.mineru().url(), "MinerU 服务地址") : prev.mineru().url(),
                cmd.mineru().tier() != null ? cmd.mineru().tier() : prev.mineru().tier(),
                cmd.mineru().timeoutSeconds() != null ? cmd.mineru().timeoutSeconds() : prev.mineru().timeoutSeconds());
        CausalConfig c = cmd.causal() == null ? prev.causal() : new CausalConfig(
                cmd.causal().enabled() != null ? cmd.causal().enabled() : prev.causal().enabled(),
                cmd.causal().url() != null ? ServiceSettings.parseHttpUri(cmd.causal().url(), "因果抽取引擎地址") : prev.causal().url(),
                IngestionMode.parse(cmd.causal().defaultMode(), prev.causal().defaultMode()),
                cmd.causal().maxCalls() != null ? cmd.causal().maxCalls() : prev.causal().maxCalls());
        ChatModelConfig ch = cmd.chat() == null ? prev.chat() : new ChatModelConfig(
                cmd.chat().baseUrl() != null ? cmd.chat().baseUrl() : prev.chat().baseUrl(),
                cmd.chat().model() != null ? cmd.chat().model() : prev.chat().model(),
                resolveKey(cmd.chat().apiKey(), cmd.chat().clearApiKey(), prev.chat().apiKey()),
                cmd.chat().enableThinking() != null ? cmd.chat().enableThinking() : prev.chat().enableThinking(),
                ThinkingStyle.parse(cmd.chat().thinkingStyle(), prev.chat().thinkingStyle()));
        EmbeddingModelConfig em = cmd.embedding() == null ? prev.embedding() : new EmbeddingModelConfig(
                cmd.embedding().baseUrl() != null ? cmd.embedding().baseUrl() : prev.embedding().baseUrl(),
                cmd.embedding().model() != null ? cmd.embedding().model() : prev.embedding().model(),
                resolveKey(cmd.embedding().apiKey(), cmd.embedding().clearApiKey(), prev.embedding().apiKey()));
        return new ServiceSettings(m, c, ch, em, prev.updatedAt(), prev.updatedBy());
    }

    private String encodeStored(ServiceSettings s) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("mineru", Map.of("url", s.mineru().url().toString(), "tier", s.mineru().tier(), "timeoutSeconds", s.mineru().timeoutSeconds()));
        root.put("causal", Map.of("enabled", s.causal().enabled(), "url", s.causal().url().toString(),
                "defaultMode", s.causal().defaultMode().name(), "maxCalls", s.causal().maxCalls()));
        root.put("chat", Map.of("baseUrl", s.chat().baseUrl(), "model", s.chat().model(),
                "apiKeyEnc", secrets.encrypt(s.chat().apiKey()),
                "enableThinking", s.chat().enableThinking(), "thinkingStyle", s.chat().thinkingStyle().name()));
        root.put("embedding", Map.of("baseUrl", s.embedding().baseUrl(), "model", s.embedding().model(),
                "apiKeyEnc", secrets.encrypt(s.embedding().apiKey())));
        try { return json.writeValueAsString(root); } catch (Exception e) { throw new IllegalStateException(e); }
    }

    private static Map<String, Object> auditSummary(ServiceSettings s) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("mineruUrl", s.mineru().url().toString());
        summary.put("causalEnabled", s.causal().enabled());
        summary.put("causalUrl", s.causal().url().toString());
        summary.put("chatBaseUrl", s.chat().baseUrl());
        summary.put("chatModel", s.chat().model());
        summary.put("chatEnableThinking", s.chat().enableThinking());
        summary.put("chatThinkingStyle", s.chat().thinkingStyle().name());
        summary.put("embeddingBaseUrl", s.embedding().baseUrl());
        summary.put("embeddingModel", s.embedding().model());
        return summary;
    }

    private static String resolveKey(String incoming, boolean clear, String previous) {
        if (clear) return "";
        return incoming != null && !incoming.isBlank() ? incoming.strip() : previous;
    }

    private static String text(JsonNode node, String field) {
        JsonNode child = node.get(field);
        return child == null || child.isNull() || child.asText().isBlank() ? null : child.asText();
    }
}

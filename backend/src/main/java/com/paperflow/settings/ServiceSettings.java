package com.paperflow.settings;

import com.paperflow.causal.model.IngestionMode;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

public record ServiceSettings(
        MineruConfig mineru,
        CausalConfig causal,
        ChatModelConfig chat,
        EmbeddingModelConfig embedding,
        Instant updatedAt,
        String updatedBy
) {
    private static final List<String> ALLOWED_TIERS = List.of("flash", "basic", "standard", "advanced");

    public record MineruConfig(URI url, String tier, int timeoutSeconds) {
        public MineruConfig {
            url = requireHttpUri(url, "MinerU 服务地址");
            tier = tier == null ? "" : tier.strip();
            if (!tier.isBlank() && !ALLOWED_TIERS.contains(tier)) {
                throw new IllegalArgumentException("MinerU 解析档位无效：" + tier);
            }
            if (timeoutSeconds < 10 || timeoutSeconds > 86400) {
                throw new IllegalArgumentException("MinerU 超时时间必须在 10 到 86400 秒之间");
            }
        }

        public Duration timeout() {
            return Duration.ofSeconds(timeoutSeconds);
        }
    }

    public record CausalConfig(boolean enabled, URI url, IngestionMode defaultMode, int maxCalls) {
        public CausalConfig {
            url = requireHttpUri(url, "因果抽取引擎地址");
            defaultMode = defaultMode == null ? IngestionMode.RAW : defaultMode;
            if (maxCalls < 1 || maxCalls > 50000) {
                throw new IllegalArgumentException("单篇模型调用上限必须在 1 到 50000 之间");
            }
        }
    }

    public record ChatModelConfig(
            String baseUrl, String model, String apiKey, boolean enableThinking, ThinkingStyle thinkingStyle
    ) {
        public ChatModelConfig {
            baseUrl = normalizeOptionalHttpUrl(baseUrl, "对话模型 API 地址");
            model = model == null ? "" : model.strip();
            apiKey = apiKey == null ? "" : apiKey.strip();
            thinkingStyle = thinkingStyle == null ? ThinkingStyle.AUTO : thinkingStyle;
        }

        public boolean configured() {
            return !baseUrl.isBlank() && !model.isBlank();
        }
    }

    public record EmbeddingModelConfig(String baseUrl, String model, String apiKey) {
        public EmbeddingModelConfig {
            baseUrl = normalizeOptionalHttpUrl(baseUrl, "嵌入模型 API 地址");
            model = model == null ? "" : model.strip();
            apiKey = apiKey == null ? "" : apiKey.strip();
        }

        public boolean configured() {
            return !baseUrl.isBlank() && !model.isBlank();
        }
    }

    public static URI parseHttpUri(String raw, String label) {
        if (raw == null || raw.isBlank()) throw new IllegalArgumentException(label + "不能为空");
        try {
            return requireHttpUri(URI.create(raw.strip()), label);
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException(label + "格式无效，请填写 http:// 或 https:// 地址");
        }
    }

    public static URI requireHttpUri(URI uri, String label) {
        if (uri == null || uri.getHost() == null || uri.getScheme() == null
                || !List.of("http", "https").contains(uri.getScheme().toLowerCase())
                || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) {
            throw new IllegalArgumentException(label + "必须是有效的 HTTP/HTTPS 地址");
        }
        return URI.create(uri.toString().replaceFirst("/+$", ""));
    }

    public static String normalizeOptionalHttpUrl(String raw, String label) {
        if (raw == null || raw.isBlank()) return "";
        return parseHttpUri(raw, label).toString();
    }
}

package com.paperflow.settings;

import java.net.InetAddress;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public enum ThinkingStyle {
    AUTO,
    THINKING_OBJECT,
    ENABLE_THINKING,
    CHAT_TEMPLATE_KWARGS,
    OLLAMA_THINK,
    REASONING_EFFORT,
    NONE;

    private static final List<String> THINKING_OBJECT_HOSTS = List.of("deepseek.com", "volces.com", "bigmodel.cn");
    private static final List<String> ENABLE_THINKING_HOSTS = List.of("dashscope.aliyuncs.com", "siliconflow.", "modelscope.");
    private static final List<String> REASONING_PREFIXES = List.of("o1", "o3", "o4", "gpt-5", "gemini-2.5", "gemini-3");

    public static ThinkingStyle parse(String raw, ThinkingStyle fallback) {
        if (raw == null || raw.isBlank()) return fallback;
        String normalized = raw.strip().toUpperCase(Locale.ROOT);
        for (ThinkingStyle style : values()) {
            if (style.name().equals(normalized)) return style;
        }
        throw new IllegalArgumentException("不支持的思考模式协议：" + raw);
    }

    public ThinkingStyle effectiveStyle(String baseUrl, String model) {
        if (this != AUTO) return this;
        URI parsed = tryParse(baseUrl);
        String host = parsed != null && parsed.getHost() != null ? parsed.getHost().toLowerCase(Locale.ROOT) : "";
        int port = parsed != null ? parsed.getPort() : -1;
        String m = model == null ? "" : model.strip().toLowerCase(Locale.ROOT);

        if (containsAny(host, THINKING_OBJECT_HOSTS)) return THINKING_OBJECT;
        if (containsAny(host, ENABLE_THINKING_HOSTS)) return ENABLE_THINKING;
        if (port == 11434 || host.contains("ollama")) return OLLAMA_THINK;
        if (host.contains("openai.com") || host.contains("generativelanguage.googleapis.com")) {
            return startsWithAny(m, REASONING_PREFIXES) ? REASONING_EFFORT : NONE;
        }
        if (isLocalHost(host)) return CHAT_TEMPLATE_KWARGS;
        if (m.contains("deepseek") || m.contains("glm-4.5") || m.contains("glm-4.6") || m.contains("glm-5")) {
            return THINKING_OBJECT;
        }
        if (m.contains("qwen3") || m.contains("qwen-3") || m.contains("qwq")) {
            return ENABLE_THINKING;
        }
        return NONE;
    }

    public Map<String, Object> resolveExtraBody(String baseUrl, String model, boolean enableThinking) {
        ThinkingStyle resolved = effectiveStyle(baseUrl, model);
        return switch (resolved) {
            case NONE, AUTO -> Map.of();
            case THINKING_OBJECT -> Map.of("thinking", Map.of("type", enableThinking ? "enabled" : "disabled"));
            case ENABLE_THINKING -> Map.of("enable_thinking", enableThinking);
            case CHAT_TEMPLATE_KWARGS -> Map.of("chat_template_kwargs", Map.of("enable_thinking", enableThinking));
            case OLLAMA_THINK -> Map.of("think", enableThinking);
            case REASONING_EFFORT -> Map.of("reasoning_effort", enableThinking ? "medium" : "none");
        };
    }

    public static Map<String, Object> mergeIntoBody(Map<String, Object> base, Map<String, Object> extra) {
        Map<String, Object> merged = new LinkedHashMap<>(base);
        if (extra != null && !extra.isEmpty()) merged.putAll(extra);
        return merged;
    }

    private static URI tryParse(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try { return URI.create(raw.strip()); } catch (Exception ignored) { return null; }
    }

    private static boolean containsAny(String value, List<String> needles) {
        for (String needle : needles) if (value.contains(needle)) return true;
        return false;
    }

    private static boolean startsWithAny(String value, List<String> prefixes) {
        for (String prefix : prefixes) if (value.startsWith(prefix)) return true;
        return false;
    }

    private static boolean isLocalHost(String host) {
        if (host.isEmpty()) return false;
        if ("localhost".equals(host) || "host.docker.internal".equals(host) || host.endsWith(".local")) return true;
        if (host.matches("^[0-9.:\\[\\]]+$")) {
            try {
                InetAddress addr = InetAddress.getByName(host);
                return addr.isLoopbackAddress() || addr.isSiteLocalAddress();
            } catch (Exception ignored) {
                return false;
            }
        }
        return false;
    }
}

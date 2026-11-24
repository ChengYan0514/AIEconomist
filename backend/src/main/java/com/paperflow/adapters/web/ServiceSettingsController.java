package com.paperflow.adapters.web;

import com.paperflow.settings.SecretBox;
import com.paperflow.settings.ServiceConnectivityProber;
import com.paperflow.settings.ServiceConnectivityProber.ProbeResult;
import com.paperflow.settings.ServiceSettings;
import com.paperflow.settings.ServiceSettingsService;
import com.paperflow.settings.ServiceSettingsService.*;
import com.paperflow.settings.ThinkingStyle;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.time.Instant;

@RestController
@RequestMapping("/api/service-settings")
public class ServiceSettingsController {
    public record MineruView(String url, String tier, int timeoutSeconds) {}
    public record CausalView(boolean enabled, String url, String defaultMode, int maxCalls) {}
    public record ChatView(String baseUrl, String model, boolean apiKeyConfigured, String apiKeyMask,
                           boolean enableThinking, String thinkingStyle, String effectiveThinkingStyle) {}
    public record EmbeddingView(String baseUrl, String model, boolean apiKeyConfigured, String apiKeyMask) {}
    public record SettingsView(MineruView mineru, CausalView causal, ChatView chat, EmbeddingView embedding,
                               Instant updatedAt, String updatedBy) {
        static SettingsView from(ServiceSettings s) {
            String chatEffective = s.chat().thinkingStyle().effectiveStyle(s.chat().baseUrl(), s.chat().model()).name();
            return new SettingsView(
                    new MineruView(s.mineru().url().toString(), s.mineru().tier(), s.mineru().timeoutSeconds()),
                    new CausalView(s.causal().enabled(), s.causal().url().toString(), s.causal().defaultMode().name(), s.causal().maxCalls()),
                    new ChatView(s.chat().baseUrl(), s.chat().model(), !s.chat().apiKey().isBlank(),
                            SecretBox.mask(s.chat().apiKey()), s.chat().enableThinking(),
                            s.chat().thinkingStyle().name(), chatEffective),
                    new EmbeddingView(s.embedding().baseUrl(), s.embedding().model(), !s.embedding().apiKey().isBlank(),
                            SecretBox.mask(s.embedding().apiKey())),
                    s.updatedAt(), s.updatedBy());
        }
    }

    public record UrlTestRequest(String url) {}
    public record ChatTestRequest(String baseUrl, String model, String apiKey, boolean clearApiKey,
                                  Boolean enableThinking, String thinkingStyle) {}
    public record EmbeddingTestRequest(String baseUrl, String model, String apiKey, boolean clearApiKey) {}

    private final ServiceSettingsService settings;
    private final ServiceConnectivityProber prober;

    public ServiceSettingsController(ServiceSettingsService settings, ServiceConnectivityProber prober) {
        this.settings = settings;
        this.prober = prober;
    }

    @GetMapping
    public SettingsView get() {
        return SettingsView.from(settings.current());
    }

    @PutMapping
    public SettingsView update(@RequestBody UpdateCommand request, Principal actor) {
        return SettingsView.from(settings.update(actor.getName(), request));
    }

    @PostMapping("/test/mineru")
    public ProbeResult testMineru(@RequestBody(required = false) UrlTestRequest req) {
        String url = req != null && req.url() != null && !req.url().isBlank()
                ? req.url() : settings.current().mineru().url().toString();
        return prober.testMineru(url);
    }

    @PostMapping("/test/causal-engine")
    public ProbeResult testCausalEngine(@RequestBody(required = false) UrlTestRequest req) {
        String url = req != null && req.url() != null && !req.url().isBlank()
                ? req.url() : settings.current().causal().url().toString();
        return prober.testCausalEngine(url);
    }

    @PostMapping("/test/chat")
    public ProbeResult testChat(@RequestBody ChatTestRequest req) {
        var cur = settings.current().chat();
        String baseUrl = req != null && req.baseUrl() != null ? req.baseUrl() : cur.baseUrl();
        String model = req != null && req.model() != null ? req.model() : cur.model();
        String apiKey = settings.resolveChatApiKey(req == null ? null : req.apiKey(), req != null && req.clearApiKey());
        boolean enableThinking = req != null && req.enableThinking() != null ? req.enableThinking() : cur.enableThinking();
        ThinkingStyle style = ThinkingStyle.parse(req == null ? null : req.thinkingStyle(), cur.thinkingStyle());
        return prober.testChatModel(baseUrl, model, apiKey, enableThinking, style);
    }

    @PostMapping("/test/embedding")
    public ProbeResult testEmbedding(@RequestBody EmbeddingTestRequest req) {
        var cur = settings.current().embedding();
        String baseUrl = req != null && req.baseUrl() != null ? req.baseUrl() : cur.baseUrl();
        String model = req != null && req.model() != null ? req.model() : cur.model();
        String apiKey = settings.resolveEmbeddingApiKey(req == null ? null : req.apiKey(), req != null && req.clearApiKey());
        return prober.testEmbeddingModel(baseUrl, model, apiKey);
    }
}

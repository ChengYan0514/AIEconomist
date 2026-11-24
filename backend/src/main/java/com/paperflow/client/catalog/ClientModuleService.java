package com.paperflow.client.catalog;

import com.paperflow.client.citation.CitationEngine;

import java.util.List;

public final class ClientModuleService {
    public record Module(String id, String name, String description, Status status, String provider, String message) {}
    public enum Status { AVAILABLE, STUB, COMING_SOON, UNAVAILABLE }

    private final CitationEngine engine;
    private final boolean qaEnabled;
    private final boolean writingEnabled;

    public ClientModuleService(CitationEngine engine, boolean qaEnabled, boolean writingEnabled) {
        this.engine = engine;
        this.qaEnabled = qaEnabled;
        this.writingEnabled = writingEnabled;
    }

    public List<Module> list() {
        return List.of(citations(),
                planned("qa", "学科知识问答", "检索和解答学科知识问题", qaEnabled),
                planned("writing", "论文写作智能体", "协助规划和撰写论文", writingEnabled));
    }

    private Module citations() {
        String provider = engine.providerId();
        String description = "为引文陈述匹配参考文献并生成引文与参考文献列表";
        if (provider.equals("stub")) {
            return new Module("citations", "参考文献自动标注", description, Status.STUB, provider,
                    "当前运行在内置演示模式，可体验完整标注与审阅流程");
        }
        if (provider.equals("disabled")) {
            return new Module("citations", "参考文献自动标注", description, Status.UNAVAILABLE, provider,
                    "参考文献自动标注引擎未启用");
        }
        boolean healthy = engine.isHealthy();
        return new Module("citations", "参考文献自动标注", description,
                healthy ? Status.AVAILABLE : Status.UNAVAILABLE, provider, healthy ? null : "参考文献自动标注引擎暂不可用");
    }

    private Module planned(String id, String name, String description, boolean enabled) {
        return new Module(id, name, description, enabled ? Status.UNAVAILABLE : Status.COMING_SOON, null,
                enabled ? "当前能力引擎暂不可用" : null);
    }
}

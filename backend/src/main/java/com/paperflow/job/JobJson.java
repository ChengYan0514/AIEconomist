package com.paperflow.job;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

public final class JobJson {
    private final ObjectMapper mapper;

    public JobJson(ObjectMapper mapper) { this.mapper = mapper; }

    public String write(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (JsonProcessingException error) { throw new IllegalStateException("无法登记任务输入", error); }
    }

    public <T> T read(String value, Class<T> type) {
        try { return mapper.readValue(value, type); }
        catch (JsonProcessingException error) { throw new IllegalStateException("任务输入无法读取", error); }
    }

    public JsonNode tree(String value) {
        if (value == null) return null;
        try { return mapper.readTree(value); }
        catch (JsonProcessingException error) { throw new IllegalStateException("任务数据无法读取", error); }
    }
}

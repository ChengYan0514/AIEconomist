package com.paperflow.openalex;

public record WorkTopic(
        String workId,
        String topicId,
        String topicName,
        String fieldName,
        String subfieldName,
        String domainName
) {}

package com.paperflow.openalex;

import java.util.List;

public interface OpenAlexStore {
    List<Source> sources(String schema, String q);
    List<Work> snapshotWorks(String schema, String sourceId, Integer yearFrom, Integer yearTo, String after);
    List<WorkTopic> snapshotWorkTopics(String schema, List<String> workIds);
    boolean lockSource(String sourceId);
    int save(String sourceId, List<Work> works);
    int saveTopics(List<WorkTopic> topics);
    List<Work> byDoi(String sourceId, String doi);
    List<Work> byYear(String sourceId, int year);
    String boundLegacyPaper(String workId);
    int bind(String paperId, String workId);
}

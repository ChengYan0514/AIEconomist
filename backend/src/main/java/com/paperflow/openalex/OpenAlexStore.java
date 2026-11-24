package com.paperflow.openalex;

import java.util.List;

public interface OpenAlexStore {
    List<Source> sources(String schema, String q);
    List<Work> snapshotWorks(String schema, String sourceId, Integer yearFrom, Integer yearTo, String after);
    List<WorkAuthor> snapshotWorkAuthors(String schema, List<String> workIds);
    List<WorkTopic> snapshotWorkTopics(String schema, List<String> workIds);
    boolean lockSource(String sourceId);
    int saveWorks(List<Work> works);
    int saveWorkSources(String sourceId, List<String> workIds);
    int saveAuthors(List<WorkAuthor> authors);
    int saveTopics(List<WorkTopic> topics);
    List<Work> byDoi(String sourceId, String doi);
    List<Work> byYear(String sourceId, int year);
    int bind(String paperId, String workId);
}

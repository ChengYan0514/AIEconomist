package com.paperflow.paper.manage;

import com.paperflow.paper.model.Paper;
import com.paperflow.paper.model.PaperFilter;
import com.paperflow.paper.model.PaperMetadata;
import com.paperflow.paper.model.StoredOriginal;

import java.util.List;
import java.util.function.Consumer;

public interface PaperStore {
    Paper get(String schema, String id);
    List<Paper> getMany(String schema, List<String> ids);
    List<Paper> list(String schema, PaperFilter filter, long offset, int size);
    long export(String schema, PaperFilter filter, Consumer<Paper> output);
    long count(PaperFilter filter);
    List<IdentityRow> atSourceYear(String sourceId, int year);
    List<String> range(String sourceId, Integer yearFrom, Integer yearTo, int limit);
    boolean sourceExists(String schema, String sourceId);
    int add(String id, PaperMetadata data, StoredOriginal file);
    int lock(String id);
    int edit(String id, PaperMetadata data);
    int archive(String id, boolean archived);
    int replace(String id, StoredOriginal file);

    record IdentityRow(String paperId, String title, String authors) {}
}

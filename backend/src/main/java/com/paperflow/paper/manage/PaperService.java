package com.paperflow.paper.manage;

import com.paperflow.paper.model.*;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

public final class PaperService {
    private final PaperStore papers;
    private final OriginalStorage files;
    private final TransactionTemplate transactions;
    private final String openalexSchema;

    public PaperService(PaperStore papers, OriginalStorage files, TransactionTemplate transactions, String openalexSchema) {
        this.papers = papers;
        this.files = files;
        this.transactions = transactions;
        this.openalexSchema = openalexSchema;
    }

    public Paper get(String id) {
        Paper paper = papers.get(id);
        if (paper == null) throw new PaperNotFound();
        return paper;
    }

    public Map<String, Paper> getMany(List<String> ids) {
        Map<String, Paper> result = new HashMap<>();
        for (int start = 0; start < ids.size(); start += 500) {
            papers.getMany(ids.subList(start, Math.min(start + 500, ids.size())))
                    .forEach(paper -> result.put(paper.paperId(), paper));
        }
        return result;
    }

    public List<Paper> list(PaperFilter filter, long offset, int limit) {
        return papers.list(filter, offset, limit);
    }

    public long count(PaperFilter filter) {
        return papers.count(filter);
    }

    public void export(PaperFilter filter, Consumer<Paper> output) {
        transactions.executeWithoutResult(status -> papers.export(filter, output));
    }

    public List<String> range(String sourceId, Integer yearFrom, Integer yearTo, int limit) {
        return papers.range(sourceId, yearFrom, yearTo, limit);
    }

    public void validateSource(String sourceId) {
        if (!papers.sourceExists(openalexSchema, sourceId)) {
            throw new IllegalArgumentException("OpenAlex 中找不到来源 " + sourceId);
        }
    }

    public String duplicate(PaperMetadata metadata) {
        if (papers.get(metadata.paperId()) != null) return metadata.paperId();
        String identity = PaperMetadata.identity(metadata.sourceId(), metadata.year(), metadata.title(), metadata.authors());
        return papers.atSourceYear(metadata.sourceId(), metadata.year()).stream()
                .filter(row -> identity.equals(PaperMetadata.identity(metadata.sourceId(), metadata.year(), row.title(), row.authors())))
                .map(PaperStore.IdentityRow::paperId).findFirst().orElse(null);
    }

    public Paper create(PaperMetadata metadata, InputStream document) throws IOException {
        return create(metadata, document, "PDF");
    }

    public Paper create(PaperMetadata metadata, InputStream document, String type) throws IOException {
        validateSource(metadata.sourceId());
        String id = metadata.paperId();
        if (duplicate(metadata) != null) throw duplicatePaper();
        StoredOriginal stored = files.save(document, id, type);
        try {
            return transactions.execute(status -> {
                if (papers.add(id, metadata, stored) != 1) throw duplicatePaper();
                return get(id);
            });
        } catch (RuntimeException error) {
            files.discardNewFile(stored.path());
            throw error;
        }
    }

    public Paper replace(String id, long version, InputStream document) throws IOException {
        get(id).requireCurrent(version);
        StoredOriginal stored = files.save(document, id, "PDF");
        try {
            return transactions.execute(status -> {
                Paper current = lock(id, version);
                current.requireActive();
                papers.replace(id, stored);
                return get(id);
            });
        } catch (RuntimeException error) {
            files.discardNewFile(stored.path());
            throw error;
        }
    }

    public Paper edit(String id, long version, PaperMetadata metadata) {
        validateSource(metadata.sourceId());
        return transactions.execute(status -> {
            lock(id, version).requireActive();
            papers.edit(id, metadata);
            return get(id);
        });
    }

    public Paper archive(String id, long version, boolean archived) {
        return transactions.execute(status -> {
            Paper current = lock(id, version);
            if (current.archived() != archived) papers.archive(id, archived);
            return get(id);
        });
    }

    public Paper lock(String id, long expectedVersion) {
        Paper before = get(id);
        before.requireCurrent(expectedVersion);
        if (before.legacy() && before.version() == 0) papers.registerLegacy(id);
        papers.lock(id);
        Paper after = get(id);
        long version = expectedVersion == 0 ? 1 : expectedVersion;
        after.requireCurrent(version);
        return after;
    }

    public Paper lockCurrent(String id) {
        Paper before = get(id);
        return lock(id, before.version());
    }

    private PaperConflict duplicatePaper() {
        return new PaperConflict("DUPLICATE_PAPER", "该论文已经登记，请在详情页替换原件");
    }
}

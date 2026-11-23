package com.paperflow.paper.manage;

import com.paperflow.audit.AuditLog;
import com.paperflow.audit.model.AuditOutcome;
import com.paperflow.paper.model.*;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

public final class PaperService {
    private final PaperStore papers;
    private final OriginalStorage files;
    private final TransactionTemplate transactions;
    private final String openalexSchema;
    private final AuditLog audit;

    public PaperService(PaperStore papers, OriginalStorage files, TransactionTemplate transactions, String openalexSchema, AuditLog audit) {
        this.papers = papers;
        this.files = files;
        this.transactions = transactions;
        this.openalexSchema = openalexSchema;
        this.audit = audit;
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

    public long export(PaperFilter filter, Consumer<Paper> output) {
        return transactions.execute(status -> papers.export(filter, output));
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

    public Paper create(PaperMetadata metadata, InputStream document, String actor) throws IOException {
        return create(metadata, document, "PDF", paper -> record(actor, "PAPER_IMPORT", paper.paperId(),
                Map.of("version", paper.version(), "revision", paper.revision())));
    }

    public Paper create(PaperMetadata metadata, InputStream document, String type, Consumer<Paper> afterCreate) throws IOException {
        validateSource(metadata.sourceId());
        String id = metadata.paperId();
        if (duplicate(metadata) != null) throw duplicatePaper();
        StoredOriginal stored = files.save(document, id, type);
        try {
            return transactions.execute(status -> {
                if (papers.add(id, metadata, stored) != 1) throw duplicatePaper();
                Paper created = get(id);
                afterCreate.accept(created);
                return created;
            });
        } catch (RuntimeException error) {
            files.discardNewFile(stored.path());
            throw error;
        }
    }

    public Paper replace(String id, long version, InputStream document, String actor) throws IOException {
        get(id).requireCurrent(version);
        StoredOriginal stored = files.save(document, id, "PDF");
        try {
            return transactions.execute(status -> {
                Paper current = lock(id, version);
                current.requireActive();
                papers.replace(id, stored);
                Paper replaced = get(id);
                record(actor, "PAPER_REPLACE", id, Map.of("previousRevision", current.revision(),
                        "revision", replaced.revision(), "version", replaced.version()));
                return replaced;
            });
        } catch (RuntimeException error) {
            files.discardNewFile(stored.path());
            throw error;
        }
    }

    public Paper edit(String id, long version, PaperMetadata metadata, String actor) {
        validateSource(metadata.sourceId());
        return transactions.execute(status -> {
            Paper current = lock(id, version);
            current.requireActive();
            papers.edit(id, metadata);
            Paper edited = get(id);
            record(actor, "PAPER_EDIT", id, Map.of("version", edited.version(), "changedFields", changedFields(current, edited)));
            return edited;
        });
    }

    public Paper archive(String id, long version, boolean archived, String actor) {
        return transactions.execute(status -> {
            Paper current = lock(id, version);
            if (current.archived() == archived) return current;
            papers.archive(id, archived);
            Paper updated = get(id);
            record(actor, archived ? "PAPER_ARCHIVE" : "PAPER_RESTORE", id, Map.of("version", updated.version()));
            return updated;
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

    private void record(String actor, String action, String id, Map<String, Object> summary) {
        audit.record(actor, action, "PAPER", id, AuditOutcome.SUCCEEDED, summary);
    }

    private List<String> changedFields(Paper before, Paper after) {
        var changed = new ArrayList<String>();
        if (!Objects.equals(before.sourceId(), after.sourceId())) changed.add("sourceId");
        if (!Objects.equals(before.year(), after.year())) changed.add("year");
        if (!Objects.equals(before.title(), after.title())) changed.add("title");
        if (!Objects.equals(before.authors(), after.authors())) changed.add("authors");
        if (!Objects.equals(before.doi(), after.doi())) changed.add("doi");
        if (!Objects.equals(before.url(), after.url())) changed.add("url");
        if (!Objects.equals(before.provider(), after.provider())) changed.add("provider");
        return changed;
    }

    private PaperConflict duplicatePaper() {
        return new PaperConflict("DUPLICATE_PAPER", "该论文已经登记，请在详情页替换原件");
    }
}

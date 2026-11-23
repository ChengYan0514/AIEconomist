package com.paperflow.openalex;

import com.paperflow.audit.AuditLog;
import com.paperflow.audit.model.AuditOutcome;
import com.paperflow.paper.manage.PaperService;
import com.paperflow.paper.model.Paper;
import com.paperflow.paper.model.PaperConflict;
import com.paperflow.paper.model.PaperMetadata;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

public final class OpenAlexService {
    private final OpenAlexStore catalog;
    private final PaperService papers;
    private final TransactionTemplate transactions;
    private final String schema;
    private final AuditLog audit;

    public OpenAlexService(OpenAlexStore catalog, PaperService papers, TransactionTemplate transactions, String schema, AuditLog audit) {
        this.catalog = catalog;
        this.papers = papers;
        this.transactions = transactions;
        this.schema = schema;
        this.audit = audit;
    }

    public List<Source> sources(String q) {
        String term = q == null ? "" : q.strip();
        if (term.length() > 200) throw new IllegalArgumentException("搜索词最多 200 个字符");
        return catalog.sources(schema, term);
    }

    public long importWorks(String source, Integer from, Integer to, Runnable checkClaim) {
        papers.validateSource(source);
        long imported = 0;
        String after = "";
        while (true) {
            checkClaim.run();
            List<Work> batch = catalog.snapshotWorks(schema, source, from, to, after);
            if (batch.isEmpty()) return imported;
            var rows = batch.stream().map(work -> new Work(work.workId(), work.year(), work.title(),
                    PaperMetadata.doi(work.doi()), work.authors())).toList();
            catalog.save(source, rows);
            after = batch.get(batch.size() - 1).workId();
            imported += batch.size();
        }
    }

    public MatchResult match(String id, long expectedVersion, Runnable checkClaim, Consumer<MatchResult> afterMatch) {
        Paper paper = papers.get(id);
        paper.requireActive();
        if (paper.matchedWorkId() != null) return new MatchResult("已有关联", List.of(), false);
        paper.requireCurrent(expectedVersion);
        List<Work> proposals = candidates(paper);
        if (proposals.isEmpty()) return new MatchResult("未找到匹配的 OpenAlex 作品", proposals, false);
        if (proposals.size() > 1) return new MatchResult("找到多个候选，请确认关联", proposals, false);
        try {
            var matched = new MatchResult("匹配成功", proposals, true);
            bind(id, expectedVersion, proposals.get(0).workId(), checkClaim, result -> afterMatch.accept(matched));
            return matched;
        } catch (PaperConflict conflict) {
            if (!"WORK_ALREADY_BOUND".equals(conflict.code())) throw conflict;
            return new MatchResult("候选作品已有论文关联", List.of(), false);
        }
    }

    public Paper confirm(String id, long expectedVersion, String workId, String actor) {
        Paper paper = papers.get(id);
        paper.requireCurrent(expectedVersion);
        if (workId == null || candidates(paper).stream().noneMatch(work -> work.workId().equals(workId))) {
            throw new PaperConflict("CANDIDATE_MISSING", "候选已变化，请重新匹配");
        }
        return bind(id, expectedVersion, workId, () -> {}, updated ->
                audit.record(actor, "PAPER_MATCH_CONFIRM", "PAPER", id, AuditOutcome.SUCCEEDED,
                        Map.of("workId", updated.matchedWorkId(), "version", updated.version())));
    }

    private List<Work> candidates(Paper paper) {
        if (paper.sourceId() == null) return List.of();
        String doi = PaperMetadata.doi(paper.doi());
        if (doi != null) {
            List<Work> exact = catalog.byDoi(paper.sourceId(), doi);
            if (!exact.isEmpty()) return exact;
        }
        if (paper.year() == null || paper.title() == null || paper.authors() == null) return List.of();
        String title = PaperMetadata.canonical(paper.title());
        List<String> authors = names(paper.authors());
        if (authors.isEmpty()) return List.of();
        return catalog.byYear(paper.sourceId(), paper.year()).stream()
                .filter(work -> title.equals(PaperMetadata.canonical(work.title())) && authors.equals(names(work.authors())))
                .toList();
    }

    private Paper bind(String id, long version, String workId, Runnable checkClaim, Consumer<Paper> afterBind) {
        try {
            return transactions.execute(status -> {
                checkClaim.run();
                var current = papers.lock(id, version);
                current.requireActive();
                String owner = catalog.boundLegacyPaper(workId);
                if ((current.matchedWorkId() != null && !current.matchedWorkId().equals(workId)) ||
                        (owner != null && !Objects.equals(owner, id))) throw owned();
                catalog.bind(id, workId);
                Paper updated = papers.get(id);
                afterBind.accept(updated);
                return updated;
            });
        } catch (DuplicateKeyException error) {
            throw owned();
        }
    }

    private static List<String> names(String authors) {
        return Arrays.stream(PaperMetadata.orderedAuthors(authors).split(";"))
                .filter(value -> !value.isEmpty()).distinct().sorted().toList();
    }

    private static PaperConflict owned() {
        return new PaperConflict("WORK_ALREADY_BOUND", "该作品已有论文关联");
    }

    public record MatchResult(String message, List<Work> candidates, boolean matched) {}
}

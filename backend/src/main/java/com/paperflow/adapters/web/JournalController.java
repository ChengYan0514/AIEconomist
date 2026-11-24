package com.paperflow.adapters.web;

import com.paperflow.paper.journal.CorpusJournalOverview;
import com.paperflow.paper.journal.JournalDetail;
import com.paperflow.paper.journal.JournalFilter;
import com.paperflow.paper.journal.JournalService;
import com.paperflow.paper.journal.JournalSummary;
import com.paperflow.paper.journal.JournalYearStats;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.List;

@RestController
@RequestMapping("/api/journals")
public class JournalController {
    private final JournalService journals;

    public JournalController(JournalService journals) {
        this.journals = journals;
    }

    record JournalDirectoryView(List<JournalSummaryView> items, int page, int size, long total,
                                CorpusOverviewView summary) {}

    record CorpusOverviewView(long totalJournals, long totalPapers, long publishedPapers, long matchedPapers,
                              long failedPapers, Integer yearMin, Integer yearMax) {
        static CorpusOverviewView from(CorpusJournalOverview source) {
            return new CorpusOverviewView(source.totalJournals(), source.totalPapers(), source.publishedPapers(),
                    source.matchedPapers(), source.failedPapers(), source.yearMin(), source.yearMax());
        }
    }

    record JournalSummaryView(String sourceId, String name, long totalPapers, long archivedPapers,
                              long publishedCount, long readyCount, long unprocessedCount, long failedCount,
                              long unsupportedCount, long matchedCount, long causalExtractedCount, Integer yearMin, Integer yearMax,
                              OffsetDateTime latestUpdatedAt) {
        static JournalSummaryView from(JournalSummary source) {
            return new JournalSummaryView(source.sourceId(), source.name(), source.totalPapers(),
                    source.archivedPapers(), source.publishedCount(), source.readyCount(), source.unprocessedCount(),
                    source.failedCount(), source.unsupportedCount(), source.matchedCount(), source.causalExtractedCount(),
                    source.yearMin(), source.yearMax(), source.latestUpdatedAt());
        }
    }

    record JournalYearView(int year, long total, long published, long ready, long unprocessed,
                           long failed, long unsupported, long matched) {
        static JournalYearView from(JournalYearStats source) {
            return new JournalYearView(source.year(), source.total(), source.published(), source.ready(),
                    source.unprocessed(), source.failed(), source.unsupported(), source.matched());
        }
    }

    record JournalDetailView(JournalSummaryView journal, long importedWorksCount, List<JournalYearView> years) {
        static JournalDetailView from(JournalDetail detail) {
            return new JournalDetailView(JournalSummaryView.from(detail.journal()), detail.importedWorksCount(),
                    detail.years().stream().map(JournalYearView::from).toList());
        }
    }

    @GetMapping
    JournalDirectoryView list(@RequestParam(required = false) String q,
                              @RequestParam(required = false) JournalFilter.Mode filter,
                              @RequestParam(required = false) JournalFilter.Sort sort,
                              @RequestParam(defaultValue = "0") int page,
                              @RequestParam(defaultValue = "20") int size) {
        long offset = Page.offset(page, size);
        var criteria = new JournalFilter(q, filter, sort);
        var items = journals.list(criteria, offset, size).stream().map(JournalSummaryView::from).toList();
        return new JournalDirectoryView(items, page, size, journals.count(criteria),
                CorpusOverviewView.from(journals.overview()));
    }

    @GetMapping("/{sourceId}")
    JournalDetailView get(@PathVariable String sourceId) {
        return JournalDetailView.from(journals.detail(sourceId));
    }
}

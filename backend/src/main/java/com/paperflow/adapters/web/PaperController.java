package com.paperflow.adapters.web;

import com.paperflow.adapters.files.FileStorage;
import com.paperflow.paper.manage.PaperService;
import com.paperflow.paper.model.Paper;
import com.paperflow.paper.model.PaperFilter;
import com.paperflow.paper.model.PaperMetadata;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.security.Principal;

@RestController
@RequestMapping("/api/papers")
public class PaperController {
    private final PaperService papers;
    private final FileStorage files;
    private final AuditAttempt audit;

    public PaperController(PaperService papers, FileStorage files, AuditAttempt audit) {
        this.papers = papers;
        this.files = files;
        this.audit = audit;
    }

    record MetadataRequest(String sourceId, Integer year, String title, String authors, String doi, String url,
                           String provider, Long version) {
        PaperMetadata toMetadata() {
            if (year == null) throw new IllegalArgumentException("请输入发表年份");
            return new PaperMetadata(sourceId, year, title, authors, doi, url, provider);
        }
    }

    @GetMapping
    Page<PaperView> list(@ModelAttribute PaperFilters criteria,
                        @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        PaperFilter filter = criteria.toFilter();
        var rows = papers.list(filter, Page.offset(page, size), size).stream()
                .map(paper -> PaperView.from(paper, paper.originalPath() != null)).toList();
        return new Page<>(rows, page, size, papers.count(filter));
    }

    @GetMapping("/{paperId}")
    PaperView get(@PathVariable String paperId) {
        return view(papers.get(paperId));
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    PaperView upload(@ModelAttribute MetadataRequest request, @RequestParam MultipartFile file, Principal actor) throws IOException {
        var metadata = audit.run(actor.getName(), "PAPER_IMPORT", "PAPER_COLLECTION", "papers", request::toMetadata);
        try (var input = file.getInputStream()) {
            return view(audit.run(actor.getName(), "PAPER_IMPORT", "PAPER", metadata.paperId(),
                    () -> papers.create(metadata, input, actor.getName())));
        }
    }

    @PatchMapping("/{paperId}")
    PaperView edit(@PathVariable String paperId, @RequestBody MetadataRequest request, Principal actor) {
        return view(audit.run(actor.getName(), "PAPER_EDIT", "PAPER", paperId, () -> {
            if (request.version() == null) throw new IllegalArgumentException("缺少论文版本");
            return papers.edit(paperId, request.version(), request.toMetadata(), actor.getName());
        }));
    }

    @PostMapping("/{paperId}/archive")
    PaperView archive(@PathVariable String paperId, @RequestParam long version, Principal actor) {
        return view(audit.run(actor.getName(), "PAPER_ARCHIVE", "PAPER", paperId,
                () -> papers.archive(paperId, version, true, actor.getName())));
    }

    @DeleteMapping("/{paperId}/archive")
    PaperView restore(@PathVariable String paperId, @RequestParam long version, Principal actor) {
        return view(audit.run(actor.getName(), "PAPER_RESTORE", "PAPER", paperId,
                () -> papers.archive(paperId, version, false, actor.getName())));
    }

    @PutMapping(path = "/{paperId}/file", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    PaperView replace(@PathVariable String paperId, @RequestParam long version, @RequestParam MultipartFile file, Principal actor)
            throws IOException {
        try (var input = file.getInputStream()) {
            return view(audit.run(actor.getName(), "PAPER_REPLACE", "PAPER", paperId,
                    () -> papers.replace(paperId, version, input, actor.getName())));
        }
    }

    private PaperView view(Paper paper) {
        return PaperView.from(paper, files.originalAvailable(paper));
    }
}

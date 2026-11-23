package com.paperflow.adapters.web;

import com.paperflow.adapters.files.FileStorage;
import com.paperflow.paper.manage.PaperService;
import com.paperflow.paper.model.Paper;
import com.paperflow.paper.model.PaperFilter;
import com.paperflow.paper.model.PaperMetadata;
import com.paperflow.paper.model.PaperStatus;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

@RestController
@RequestMapping("/api/papers")
public class PaperController {
    private final PaperService papers;
    private final FileStorage files;

    public PaperController(PaperService papers, FileStorage files) {
        this.papers = papers;
        this.files = files;
    }

    record Filters(String q, String sourceId, Integer yearFrom, Integer yearTo, PaperStatus status, Boolean archived) {
        PaperFilter toFilter() {
            return new PaperFilter(q, sourceId, yearFrom, yearTo, status, Boolean.TRUE.equals(archived));
        }
    }

    record MetadataRequest(String sourceId, Integer year, String title, String authors, String doi, String url,
                           String provider, Long version) {
        PaperMetadata toMetadata() {
            if (year == null) throw new IllegalArgumentException("请输入发表年份");
            return new PaperMetadata(sourceId, year, title, authors, doi, url, provider);
        }
    }

    @GetMapping
    Page<PaperView> list(@ModelAttribute Filters criteria,
                        @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        PaperFilter filter = criteria.toFilter();
        var rows = papers.list(filter, Page.offset(page, size), size).stream()
                .map(paper -> PaperView.from(paper, paper.originalPath() != null)).toList();
        return new Page<>(rows, page, size, papers.count(filter));
    }

    @GetMapping("/export")
    ResponseEntity<StreamingResponseBody> export(@ModelAttribute Filters criteria) {
        var filter = criteria.toFilter();
        StreamingResponseBody stream = output -> {
            var writer = new OutputStreamWriter(output, StandardCharsets.UTF_8);
            writer.write('\uFEFF');
            var format = CSVFormat.DEFAULT.builder()
                    .setHeader("paper_id", "source_id", "year", "paper_title", "authors", "doi", "status").build();
            try (var csv = new CSVPrinter(writer, format)) {
                papers.export(filter, paper -> writeRow(csv, paper));
            } catch (UncheckedIOException error) {
                throw error.getCause();
            }
        };
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=paperflow-papers.csv")
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8)).body(stream);
    }

    @GetMapping("/{paperId}")
    PaperView get(@PathVariable String paperId) {
        return view(papers.get(paperId));
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    PaperView upload(@ModelAttribute MetadataRequest request, @RequestParam MultipartFile file) throws IOException {
        try (var input = file.getInputStream()) {
            return view(papers.create(request.toMetadata(), input));
        }
    }

    @PatchMapping("/{paperId}")
    PaperView edit(@PathVariable String paperId, @RequestBody MetadataRequest request) {
        if (request.version() == null) throw new IllegalArgumentException("缺少论文版本");
        return view(papers.edit(paperId, request.version(), request.toMetadata()));
    }

    @PostMapping("/{paperId}/archive")
    PaperView archive(@PathVariable String paperId, @RequestParam long version) {
        return view(papers.archive(paperId, version, true));
    }

    @DeleteMapping("/{paperId}/archive")
    PaperView restore(@PathVariable String paperId, @RequestParam long version) {
        return view(papers.archive(paperId, version, false));
    }

    @PutMapping(path = "/{paperId}/file", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    PaperView replace(@PathVariable String paperId, @RequestParam long version, @RequestParam MultipartFile file)
            throws IOException {
        try (var input = file.getInputStream()) {
            return view(papers.replace(paperId, version, input));
        }
    }

    private PaperView view(Paper paper) {
        return PaperView.from(paper, files.originalAvailable(paper));
    }

    private void writeRow(CSVPrinter csv, Paper paper) {
        try {
            csv.printRecord(spreadsheet(paper.paperId()), spreadsheet(paper.sourceId()), paper.year(),
                    spreadsheet(paper.title()), spreadsheet(paper.authors()), spreadsheet(paper.doi()), paper.status().name());
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }

    private String spreadsheet(String value) {
        if (value == null || value.isEmpty()) return value;
        String significant = value.stripLeading();
        boolean formula = !significant.isEmpty() && "=+-@".indexOf(significant.charAt(0)) >= 0;
        return formula || "\t\r".indexOf(value.charAt(0)) >= 0 ? "'" + value : value;
    }
}

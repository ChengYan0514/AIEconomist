package com.paperflow.adapters.web;

import com.paperflow.audit.AuditLog;
import com.paperflow.audit.model.AuditOutcome;
import com.paperflow.paper.manage.PaperService;
import com.paperflow.paper.model.Paper;
import com.paperflow.paper.model.PaperFilter;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindException;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
public class PaperCsvController {
    private static final CSVFormat FORMAT = CSVFormat.DEFAULT.builder()
            .setHeader("paper_id", "source_id", "year", "paper_title", "authors", "doi", "status").build();
    private final PaperService papers;
    private final AuditLog audit;

    public PaperCsvController(PaperService papers, AuditLog audit) {
        this.papers = papers;
        this.audit = audit;
    }

    @GetMapping("/api/papers/export")
    ResponseEntity<StreamingResponseBody> export(@ModelAttribute PaperFilters criteria,
                                                  BindingResult errors, Principal principal) throws BindException {
        String actor = principal.getName();
        PaperFilter filter;
        try {
            if (errors.hasErrors()) throw new BindException(errors);
            filter = criteria.toFilter();
        } catch (BindException | IllegalArgumentException error) {
            recordFailure(actor, "INVALID_INPUT", error);
            throw error;
        }
        return csvResponse().body(output -> stream(output, filter, actor));
    }

    @RequestMapping(path = "/api/papers/export", method = RequestMethod.HEAD)
    ResponseEntity<Void> exportHeaders(@ModelAttribute PaperFilters criteria) {
        criteria.toFilter();
        return csvResponse().build();
    }

    private ResponseEntity.BodyBuilder csvResponse() {
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=paperflow-papers.csv")
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8));
    }

    private void stream(OutputStream output, PaperFilter filter, String actor) throws IOException {
        long rows;
        try {
            var writer = new OutputStreamWriter(output, StandardCharsets.UTF_8);
            writer.write('\uFEFF');
            var csv = new CSVPrinter(writer, FORMAT);
            rows = papers.export(filter, paper -> writeRow(csv, paper));
            csv.flush();
        } catch (UncheckedIOException error) {
            IOException failure = error.getCause();
            recordFailure(actor, "EXPORT_FAILED", failure);
            throw failure;
        } catch (IOException | RuntimeException error) {
            recordFailure(actor, "EXPORT_FAILED", error);
            throw error;
        }
        record(actor, AuditOutcome.SUCCEEDED, summary(filter, rows));
    }

    private void record(String actor, AuditOutcome outcome, Map<String, Object> summary) {
        audit.record(actor, "PAPER_CSV_EXPORT", "PAPER_COLLECTION", "papers", outcome, summary);
    }

    private void recordFailure(String actor, String code, Exception error) {
        AuditAttempt.preserveFailure(error, () -> record(actor, AuditOutcome.FAILED, Map.of("code", code)));
    }

    private Map<String, Object> summary(PaperFilter filter, long rows) {
        var result = new LinkedHashMap<String, Object>();
        result.put("exportedRows", rows);
        result.put("searchApplied", filter.q() != null);
        result.put("archived", filter.archived());
        if (filter.sourceId() != null) result.put("sourceId", filter.sourceId());
        if (filter.yearFrom() != null) result.put("yearFrom", filter.yearFrom());
        if (filter.yearTo() != null) result.put("yearTo", filter.yearTo());
        if (filter.status() != null) result.put("status", filter.status().name());
        if (filter.causalStatus() != null) result.put("causalStatus", filter.causalStatus().name());
        return result;
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

package com.paperflow.adapters.web;

import com.paperflow.adapters.files.FileStorage;
import com.paperflow.openalex.OpenAlexService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api")
public class OpenAlexController {
    private final OpenAlexService openAlex;
    private final FileStorage files;

    public OpenAlexController(OpenAlexService openAlex, FileStorage files) {
        this.openAlex = openAlex;
        this.files = files;
    }

    record ConfirmMatch(String workId, Long version) {}
    record SourceView(String sourceId, String name) {}

    @GetMapping("/sources")
    List<SourceView> sources(@RequestParam(required = false) String q) {
        return openAlex.sources(q).stream().map(source -> new SourceView(source.sourceId(), source.name())).toList();
    }

    @PostMapping("/papers/{paperId}/match")
    PaperView confirm(@PathVariable String paperId, @RequestBody ConfirmMatch confirmation) {
        if (confirmation.version() == null) throw new IllegalArgumentException("缺少论文版本");
        var paper = openAlex.confirm(paperId, confirmation.version(), confirmation.workId());
        return PaperView.from(paper, files.originalAvailable(paper));
    }
}

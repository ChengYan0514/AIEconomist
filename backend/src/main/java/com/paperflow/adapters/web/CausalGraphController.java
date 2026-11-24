package com.paperflow.adapters.web;

import com.paperflow.causal.graph.CausalGraphModels.*;
import com.paperflow.causal.graph.CausalGraphService;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.List;

@RestController
@RequestMapping("/api/causal")
public class CausalGraphController {
    private final CausalGraphService service;

    public CausalGraphController(CausalGraphService service) {
        this.service = service;
    }

    @GetMapping("/summary")
    GraphSummary summary() {
        return service.summary();
    }

    @GetMapping("/graph")
    GraphData graph(
            @RequestParam(required = false) Integer minRecordCount,
            @RequestParam(required = false) Integer minPaperCount,
            @RequestParam(required = false) Integer minDiversity,
            @RequestParam(required = false) List<String> subfields,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) Integer maxNodes,
            @RequestParam(required = false) Integer maxEdges
    ) {
        return service.graph(minRecordCount, minPaperCount, minDiversity, subfields, q, maxNodes, maxEdges);
    }

    @GetMapping("/fields")
    FieldAnalysis fields() {
        return service.fields();
    }

    @GetMapping("/variables/{name}")
    NodeDetail variable(@PathVariable String name) {
        return service.variableDetail(name);
    }

    @GetMapping("/relations")
    EdgeDetail relation(@RequestParam String cause, @RequestParam String effect) {
        return service.edgeDetailByCauseEffect(cause, effect);
    }

    @GetMapping("/relations/{claimId}")
    EdgeDetail relationById(@PathVariable long claimId) {
        return service.edgeDetailByClaimId(claimId);
    }

    @GetMapping("/papers/{workId}")
    PaperDetail paper(@PathVariable String workId) {
        return service.paperDetail(workId);
    }

    @GetMapping("/papers/{workId}/summary")
    PaperSummary paperSummary(@PathVariable String workId) {
        return service.paperSummary(workId);
    }

    @GetMapping("/search/variables")
    List<NodeSearchHit> searchVariables(@RequestParam String q, @RequestParam(required = false) Integer limit) {
        return service.searchVariables(q, limit);
    }

    @GetMapping("/search/terms")
    List<String> searchTerms(@RequestParam String q, @RequestParam(required = false) Integer limit) {
        return service.searchTerms(q, limit);
    }

    @GetMapping("/search/papers")
    List<PaperSearchHit> searchPapers(@RequestParam String q, @RequestParam(required = false) Integer limit) {
        return service.searchPapers(q, limit);
    }

    @PostMapping("/read-model/refresh")
    RefreshResult refresh(Principal actor) {
        return service.refreshReadModel(actor.getName());
    }
}

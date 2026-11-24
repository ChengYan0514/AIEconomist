package com.paperflow.client.adapters.web;

import com.paperflow.client.catalog.ClientJournalService;
import com.paperflow.client.catalog.ClientJournalStore;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public final class ClientJournalController {
    private final ClientJournalService service;

    public ClientJournalController(ClientJournalService service) {
        this.service = service;
    }

    @GetMapping("/api/client/journals")
    public ClientJournalStore.Catalog search(@RequestParam(required = false) String q,
                                             @RequestParam(defaultValue = "100") int limit) {
        return service.search(q, limit);
    }
}

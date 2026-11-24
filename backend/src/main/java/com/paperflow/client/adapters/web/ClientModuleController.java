package com.paperflow.client.adapters.web;

import com.paperflow.client.catalog.ClientModuleService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public final class ClientModuleController {
    private final ClientModuleService service;

    public ClientModuleController(ClientModuleService service) {
        this.service = service;
    }

    @GetMapping("/api/client/modules")
    public Catalog list() {
        return new Catalog(service.list());
    }

    public record Catalog(List<ClientModuleService.Module> modules) {}
}

package com.paperflow.adapters.web;

import com.paperflow.systemstatus.SystemStatus;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SystemStatusController {
    private final SystemStatus status;

    public SystemStatusController(SystemStatus status) {
        this.status = status;
    }

    @GetMapping("/api/system-status")
    public ResponseEntity<SystemStatus.Report> refresh() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(status.refresh());
    }
}

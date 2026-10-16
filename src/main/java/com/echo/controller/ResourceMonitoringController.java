package com.echo.controller;

import com.echo.dto.ResourceSnapshotDto;
import com.echo.service.ResourceMonitoringService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class ResourceMonitoringController {
    private final ResourceMonitoringService monitoring;

    /** Role check only: diagnostics must remain reachable when the database status query stalls. */
    @GetMapping("/api/admin/resources/access")
    public ResponseEntity<Void> access() {
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    @GetMapping("/api/admin/resources")
    public ResponseEntity<ResourceSnapshotDto> snapshot() {
        return ResponseEntity.ok().contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .cacheControl(CacheControl.noStore()).body(monitoring.snapshot());
    }
}

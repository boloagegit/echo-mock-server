package com.echo.controller;

import com.echo.dto.GcHistoryDto;
import com.echo.service.GcMonitoringService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import static org.springframework.http.HttpStatus.BAD_REQUEST;

@RestController
@RequiredArgsConstructor
public class GcMonitoringController {
    private final GcMonitoringService monitoring;

    @GetMapping("/api/admin/resources/gc")
    public ResponseEntity<GcHistoryDto> history(@RequestParam(required = false) Long after,
                                               @RequestParam(defaultValue = "512") int limit) {
        if ((after != null && after < 0) || limit < 1 || limit > 512) {
            throw new ResponseStatusException(BAD_REQUEST, "Invalid GC history cursor or limit");
        }
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).cacheControl(CacheControl.noStore())
                .body(monitoring.snapshot(after, limit));
    }
}

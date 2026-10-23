package com.echo.controller;

import com.echo.service.SdkCatalogService;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Serves SDK artifacts embedded at build time, including from an executable Echo JAR. */
@RestController
@RequestMapping("/api/admin/sdk")
public final class SdkDownloadController {

    private final SdkCatalogService catalogService;

    public SdkDownloadController(SdkCatalogService catalogService) {
        this.catalogService = catalogService;
    }

    @GetMapping
    public ResponseEntity<Resource> catalog() {
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON)
                .cacheControl(CacheControl.noStore()).body(catalogService.catalog());
    }

    @GetMapping("/{sdkId}/releases/{version}/artifacts/{artifactId}")
    public ResponseEntity<Resource> download(@PathVariable String sdkId, @PathVariable String version,
                                             @PathVariable String artifactId) {
        return catalogService.find(sdkId, version, artifactId)
                .map(this::download).orElseGet(() -> ResponseEntity.notFound().build());
    }

    private ResponseEntity<Resource> download(SdkCatalogService.Download artifact) {
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(artifact.mediaType()))
                .contentLength(artifact.size())
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(artifact.fileName()).build().toString())
                .body(catalogService.resource(artifact));
    }
}

package com.echo.controller;

import com.echo.dto.HttpTargetConnectionRequest;
import com.echo.service.HttpOutboundForwarder;
import com.echo.service.HttpTargetConnectionService;
import jakarta.persistence.OptimisticLockException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.async.DeferredResult;

import java.util.Map;

/** Admin API for selectable outbound HTTP profiles. */
@RestController
@RequestMapping("/api/admin/http-target-connections")
@RequiredArgsConstructor
public class HttpTargetConnectionController {

    private final HttpTargetConnectionService service;
    private final HttpOutboundForwarder forwarder;

    @GetMapping
    public ResponseEntity<?> list() {
        return ResponseEntity.ok(service.list());
    }

    @GetMapping("/metrics")
    public ResponseEntity<?> metrics() {
        return ResponseEntity.ok(forwarder.metricsSnapshot());
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody HttpTargetConnectionRequest request) {
        return handle(() -> ResponseEntity.status(HttpStatus.CREATED).body(service.create(request)));
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable Long id,
                                    @RequestBody HttpTargetConnectionRequest request) {
        return handle(() -> {
            var updated = service.update(id, request);
            forwarder.evict(id);
            return ResponseEntity.ok(updated);
        });
    }

    @PutMapping("/{id}/default")
    public ResponseEntity<?> setDefault(@PathVariable Long id) {
        return handle(() -> ResponseEntity.ok(service.setDefault(id)));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable Long id) {
        return handle(() -> {
            service.delete(id);
            forwarder.evict(id);
            return ResponseEntity.ok(Map.of("deleted", true));
        });
    }

    @PostMapping("/{id}/test")
    @SuppressWarnings("FutureReturnValueIgnored")
    public DeferredResult<ResponseEntity<?>> test(@PathVariable Long id) {
        // Keep servlet workers available while the asynchronous HTTP probe waits on its peer.
        var result = new DeferredResult<ResponseEntity<?>>(forwarder.testTimeoutMillis() + 1_000);
        try {
            var probe = forwarder.testAsync(id);
            result.onCompletion(() -> probe.cancel(true));
            result.onTimeout(() -> {
                result.setResult(ResponseEntity.status(HttpStatus.GATEWAY_TIMEOUT)
                        .body(Map.of("error", "HTTP_CONNECTION_TEST_TIMEOUT")));
                probe.cancel(true);
            });
            result.onError(error -> {
                result.setErrorResult(error);
                probe.cancel(true);
            });
            probe.whenComplete((response, error) -> {
                if (error == null) result.setResult(ResponseEntity.ok(response));
                else result.setErrorResult(error);
            });
        } catch (IllegalArgumentException e) {
            result.setResult(handle(() -> { throw e; }));
        }
        return result;
    }

    private ResponseEntity<?> handle(Action action) {
        try {
            return action.execute();
        } catch (ObjectOptimisticLockingFailureException | OptimisticLockException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", "OPTIMISTIC_LOCK_CONFLICT"));
        } catch (IllegalArgumentException e) {
            if ("HTTP_CONNECTION_NOT_FOUND".equals(e.getMessage())) {
                return ResponseEntity.notFound().build();
            }
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @FunctionalInterface
    private interface Action {
        ResponseEntity<?> execute();
    }
}

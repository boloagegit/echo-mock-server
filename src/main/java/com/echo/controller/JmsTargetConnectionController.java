package com.echo.controller;

import com.echo.dto.JmsTargetConnectionRequest;
import com.echo.service.JmsTargetConnectionService;
import jakarta.persistence.OptimisticLockException;
import lombok.RequiredArgsConstructor;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Admin API for selectable outbound JMS broker profiles. */
@RestController
@RequestMapping("/api/admin/jms-target-connections")
@RequiredArgsConstructor
public class JmsTargetConnectionController {

    private final JmsTargetConnectionService service;

    @GetMapping
    public ResponseEntity<?> list() {
        return ResponseEntity.ok(service.list());
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody JmsTargetConnectionRequest request) {
        return handle(() -> ResponseEntity.status(HttpStatus.CREATED).body(service.create(request)));
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable Long id,
                                    @RequestBody JmsTargetConnectionRequest request) {
        return handle(() -> ResponseEntity.ok(service.update(id, request)));
    }

    @PutMapping("/{id}/default")
    public ResponseEntity<?> setDefault(@PathVariable Long id) {
        return handle(() -> ResponseEntity.ok(service.setDefault(id)));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable Long id) {
        return handle(() -> {
            service.delete(id);
            return ResponseEntity.ok(Map.of("deleted", true));
        });
    }

    @PostMapping("/{id}/test")
    public ResponseEntity<?> test(@PathVariable String id) {
        return handle(() -> ResponseEntity.ok(service.test(id)));
    }

    private ResponseEntity<?> handle(Action action) {
        try {
            return action.execute();
        } catch (ObjectOptimisticLockingFailureException | OptimisticLockException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", "OPTIMISTIC_LOCK_CONFLICT"));
        } catch (IllegalArgumentException e) {
            if ("JMS_CONNECTION_NOT_FOUND".equals(e.getMessage())) {
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

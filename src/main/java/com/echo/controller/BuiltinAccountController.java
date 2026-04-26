package com.echo.controller;

import com.echo.entity.BuiltinUser;
import com.echo.service.BuiltinUserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.bind.annotation.*;

import jakarta.persistence.OptimisticLockException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 內建帳號管理 REST API
 * <p>
 * 提供帳號 CRUD、啟用/停用、密碼重設、忘記密碼、自助註冊等功能。
 */
@Slf4j
@RestController
@RequestMapping(value = "/api/admin/builtin-users", produces = "application/json")
@RequiredArgsConstructor
public class BuiltinAccountController {

    private final BuiltinUserService builtinUserService;

    @Value("${echo.builtin-account.self-registration:false}")
    private boolean selfRegistrationEnabled;

    /** 公開端點速率限制：每個 IP 每分鐘最多 10 次 */
    private static final int RATE_LIMIT = 10;
    private static final long RATE_WINDOW_MS = 60_000;
    private final ConcurrentHashMap<String, long[]> rateLimitMap = new ConcurrentHashMap<>();

    private boolean isRateLimited(String ip) {
        long now = System.currentTimeMillis();
        long[] window = rateLimitMap.compute(ip, (k, v) -> {
            if (v == null || now - v[1] > RATE_WINDOW_MS) {
                return new long[]{1, now};
            }
            v[0]++;
            return v;
        });
        return window[0] > RATE_LIMIT;
    }

    @GetMapping
    public ResponseEntity<List<BuiltinUser>> listUsers() {
        return ResponseEntity.ok(builtinUserService.listUsers());
    }

    @PostMapping
    public ResponseEntity<?> createUser(@RequestBody Map<String, String> body) {
        return handleRequest(() -> {
            BuiltinUser user = builtinUserService.createUser(body.get("username"), body.get("password"));
            return ResponseEntity.status(HttpStatus.CREATED).body(user);
        });
    }

    @PutMapping("/{id}/enable")
    public ResponseEntity<?> enableUser(@PathVariable Long id) {
        return handleRequest(() -> { builtinUserService.enableUser(id); return ResponseEntity.ok().build(); });
    }

    @PutMapping("/{id}/disable")
    public ResponseEntity<?> disableUser(@PathVariable Long id) {
        return handleRequest(() -> { builtinUserService.disableUser(id); return ResponseEntity.ok().build(); });
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteUser(@PathVariable Long id) {
        return handleRequest(() -> { builtinUserService.deleteUser(id); return ResponseEntity.ok().build(); });
    }

    @PostMapping("/{id}/reset-password")
    public ResponseEntity<?> resetPassword(@PathVariable Long id) {
        return handleRequest(() -> {
            String tempPassword = builtinUserService.resetPassword(id);
            return ResponseEntity.ok(Map.of("tempPassword", tempPassword));
        });
    }

    /** 忘記密碼請求（公開）— 無論帳號是否存在都回傳相同格式 */
    @PostMapping("/forgot-password")
    public ResponseEntity<Map<String, String>> forgotPassword(@RequestBody Map<String, String> body, HttpServletRequest request) {
        if (isRateLimited(request.getRemoteAddr())) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(Map.of("error", "RATE_LIMITED"));
        }
        builtinUserService.requestPasswordReset(body.get("username"));
        return ResponseEntity.ok(Map.of("message", "OK"));
    }

    /** 自助註冊（公開，需啟用 echo.builtin-account.self-registration） */
    @PostMapping("/register")
    public ResponseEntity<?> selfRegister(@RequestBody Map<String, String> body, HttpServletRequest request) {
        if (isRateLimited(request.getRemoteAddr())) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(Map.of("error", "RATE_LIMITED"));
        }
        if (!selfRegistrationEnabled) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "SELF_REGISTRATION_DISABLED"));
        }
        try {
            BuiltinUser user = builtinUserService.createUser(body.get("username"), body.get("password"));
            return ResponseEntity.status(HttpStatus.CREATED).body(user);
        } catch (IllegalArgumentException e) {
            return mapErrorResponse(e.getMessage());
        }
    }

    /** 統一處理 IllegalArgumentException + 樂觀鎖衝突 */
    private ResponseEntity<?> handleRequest(RequestAction action) {
        try {
            return action.execute();
        } catch (IllegalArgumentException e) {
            return mapErrorResponse(e.getMessage());
        } catch (ObjectOptimisticLockingFailureException | OptimisticLockException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", "OPTIMISTIC_LOCK_CONFLICT"));
        }
    }

    private ResponseEntity<Map<String, String>> mapErrorResponse(String errorCode) {
        HttpStatus status = switch (errorCode) {
            case "USERNAME_EXISTS" -> HttpStatus.CONFLICT;
            case "USERNAME_LENGTH", "PASSWORD_TOO_SHORT", "CANNOT_DELETE_ONLY_ADMIN", "OLD_PASSWORD_INCORRECT" -> HttpStatus.BAD_REQUEST;
            case "USER_NOT_FOUND" -> HttpStatus.NOT_FOUND;
            default -> HttpStatus.BAD_REQUEST;
        };
        return ResponseEntity.status(status).body(Map.of("error", errorCode));
    }

    @FunctionalInterface
    private interface RequestAction {
        ResponseEntity<?> execute();
    }
}

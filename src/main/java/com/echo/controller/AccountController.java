package com.echo.controller;

import com.echo.service.BuiltinUserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.Map;

/**
 * 使用者帳號操作 REST API
 * <p>
 * 提供已登入使用者自行修改密碼等功能，路徑為 /api/account/**。
 */
@Slf4j
@RestController
@RequestMapping(value = "/api/account", produces = "application/json")
@RequiredArgsConstructor
public class AccountController {

    private final BuiltinUserService builtinUserService;

    /**
     * 已登入使用者自行修改密碼
     */
    @PutMapping("/change-password")
    public ResponseEntity<?> changePassword(@RequestBody Map<String, String> body, Principal principal) {
        String username = principal.getName();
        String oldPassword = body.get("oldPassword");
        String newPassword = body.get("newPassword");
        try {
            builtinUserService.changePassword(username, oldPassword, newPassword);
            return ResponseEntity.ok(Map.of("message", "OK"));
        } catch (IllegalArgumentException e) {
            HttpStatus status = switch (e.getMessage()) {
                case "OLD_PASSWORD_INCORRECT", "PASSWORD_TOO_SHORT" -> HttpStatus.BAD_REQUEST;
                case "USER_NOT_FOUND" -> HttpStatus.NOT_FOUND;
                default -> HttpStatus.BAD_REQUEST;
            };
            return ResponseEntity.status(status).body(Map.of("error", e.getMessage()));
        }
    }
}

package com.echo.config;

import com.echo.repository.BuiltinUserRepository;
import com.echo.service.BuiltinUserService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.security.authentication.event.AuthenticationSuccessEvent;
import org.springframework.stereotype.Component;

/**
 * 監聽認證成功事件，更新內建帳號的最後登入時間。
 */
@Component
@RequiredArgsConstructor
public class AuthenticationEventListener {

    private final BuiltinUserService builtinUserService;
    private final BuiltinUserRepository builtinUserRepository;

    @EventListener
    public void onAuthenticationSuccess(AuthenticationSuccessEvent event) {
        String username = event.getAuthentication().getName();
        // 只有內建帳號才需要更新 lastLoginAt，避免對 LDAP/in-memory 帳號做無意義的 DB 查詢
        if (builtinUserRepository.existsByUsername(username)) {
            builtinUserService.updateLastLogin(username);
        }
    }
}

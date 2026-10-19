package com.echo.config;

import com.echo.repository.BuiltinUserRepository;
import com.echo.service.BuiltinUserService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.security.authentication.event.AuthenticationSuccessEvent;
import org.springframework.stereotype.Component;

/**
 * Monitor the authentication success event and update the last login time of the built-in account.
 */
@Component
@RequiredArgsConstructor
public class AuthenticationEventListener {

    private final BuiltinUserService builtinUserService;
    private final BuiltinUserRepository builtinUserRepository;

    @EventListener
    public void onAuthenticationSuccess(AuthenticationSuccessEvent event) {
        String username = event.getAuthentication().getName();
        // Only built-in accounts need to update lastLoginAt to avoid meaningless DB queries for LDAP/in-memory accounts
        if (builtinUserRepository.existsByUsername(username)) {
            builtinUserService.updateLastLogin(username);
        }
    }
}

package com.echo.util;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/** Resolves the authenticated operator for persistent audit metadata. */
public final class CurrentOperator {

    private CurrentOperator() {
    }

    public static String username() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.isAuthenticated()
                && !"anonymousUser".equals(authentication.getPrincipal())
                && authentication.getName() != null && !authentication.getName().isBlank()) {
            return authentication.getName();
        }
        return "system";
    }
}

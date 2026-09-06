package com.echo.dto;

import com.echo.entity.BuiltinUser;

import java.time.LocalDateTime;

/** Safe account-list projection that never exposes password data. */
public record BuiltinUserSummaryDto(
        Long id,
        String username,
        String role,
        Boolean enabled,
        Boolean passwordResetRequested,
        LocalDateTime passwordResetRequestedAt,
        Boolean forceChangePassword,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        LocalDateTime lastLoginAt
) {
    public static BuiltinUserSummaryDto from(BuiltinUser user) {
        return new BuiltinUserSummaryDto(
                user.getId(),
                user.getUsername(),
                user.getRole(),
                user.getEnabled(),
                user.getPasswordResetRequested(),
                user.getPasswordResetRequestedAt(),
                user.getForceChangePassword(),
                user.getCreatedAt(),
                user.getUpdatedAt(),
                user.getLastLoginAt());
    }
}

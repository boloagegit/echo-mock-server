package com.echo.repository;

import com.echo.entity.BuiltinUser;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface BuiltinUserRepository extends JpaRepository<BuiltinUser, Long> {

    Optional<BuiltinUser> findByUsername(String username);

    boolean existsByUsername(String username);

    List<BuiltinUser> findByUsernameContainingIgnoreCase(String keyword);

    long countByRole(String role);

    @Query("SELECT u FROM BuiltinUser u WHERE " +
           "(:keyword IS NULL OR LOWER(u.username) LIKE :keyword ESCAPE '\\') AND " +
           "(:role IS NULL OR u.role = :role) AND " +
           "(:enabled IS NULL OR u.enabled = :enabled) AND " +
           "(:resetRequested IS NULL OR u.passwordResetRequested = :resetRequested)")
    Page<BuiltinUser> queryUsers(@Param("keyword") String keyword,
                                 @Param("role") String role,
                                 @Param("enabled") Boolean enabled,
                                 @Param("resetRequested") Boolean resetRequested,
                                 Pageable pageable);
}

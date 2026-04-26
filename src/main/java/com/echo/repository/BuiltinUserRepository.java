package com.echo.repository;

import com.echo.entity.BuiltinUser;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface BuiltinUserRepository extends JpaRepository<BuiltinUser, Long> {

    Optional<BuiltinUser> findByUsername(String username);

    boolean existsByUsername(String username);

    List<BuiltinUser> findByUsernameContainingIgnoreCase(String keyword);

    long countByRole(String role);
}

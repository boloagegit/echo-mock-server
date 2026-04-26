package com.echo.config;

import com.echo.entity.BuiltinUser;
import com.echo.repository.BuiltinUserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configuration.WebSecurityCustomizer;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.annotation.web.configurers.HeadersConfigurer;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.config.Customizer;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;

import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import java.util.Optional;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Value("${echo.admin.username:admin}")
    private String adminUsername;

    @Value("${echo.admin.password:admin}")
    private String adminPassword;

    @Value("${echo.remember-me.key:echo-remember-me-secret}")
    private String rememberMeKey;

    @Value("${echo.remember-me.validity:${server.servlet.session.timeout:30m}}")
    private Duration rememberMeValidity;

    /**
     * 唯讀開放，修改需登入
     */
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, 
            AuthenticationProvider ldapAuthenticationProvider,
            AuthenticationProvider daoAuthenticationProvider,
            UserDetailsService inMemoryUserDetailsService) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            .headers(h -> h.frameOptions(HeadersConfigurer.FrameOptionsConfig::sameOrigin))
            .authenticationProvider(ldapAuthenticationProvider)
            .authenticationProvider(daoAuthenticationProvider)
            .authorizeHttpRequests(auth -> auth
                // 完全開放
                .requestMatchers("/webjars/**", "/login.html", "/*.css", "/*.js", "/composables/*.js", "/favicon.svg", "/favicon.ico", "/i18n/**", "/components/**", "/api/auth/**").permitAll()
                // 忘記密碼、自助註冊 — 公開（必須在 builtin-users/** 之前）
                .requestMatchers("/api/admin/builtin-users/forgot-password", "/api/admin/builtin-users/register").permitAll()
                // 內建帳號管理 — 僅 ADMIN
                .requestMatchers("/api/admin/builtin-users/**").hasRole("ADMIN")
                // 使用者自行修改密碼 — 已登入即可
                .requestMatchers("/api/account/change-password").authenticated()
                // 批次操作僅 ADMIN (必須在 permitAll 之前)
                .requestMatchers("/api/admin/rules/export", "/api/admin/rules/import-batch", "/api/admin/rules/import-excel", "/api/admin/rules/batch", "/api/admin/rules/batch/*", "/api/admin/rules/tag/*/*/*", "/api/admin/rules/all", "/api/admin/responses/all", "/api/admin/responses/export", "/api/admin/audit/all", "/api/admin/setup").hasRole("ADMIN")
                // 唯讀 API 開放
                .requestMatchers(HttpMethod.GET, "/", "/index.html", "/api/admin/status", "/api/admin/rules", "/api/admin/rules/*", "/api/admin/rules/import-template", "/api/admin/rules/*/audit", "/api/admin/audit", "/api/admin/logs", "/api/admin/logs/summary", "/api/admin/logs/*/detail", "/api/admin/responses", "/api/admin/responses/*", "/api/admin/responses/*/rules", "/api/admin/responses/summary").permitAll()
                // 其他需登入 (POST/PUT/DELETE)
                .anyRequest().authenticated()
            )
            .exceptionHandling(ex -> ex
                .authenticationEntryPoint(noPopupEntryPoint())
            )
            .httpBasic(basic -> basic
                .authenticationEntryPoint(noPopupEntryPoint())
            )
            .formLogin(form -> form
                .loginPage("/login.html")
                .loginProcessingUrl("/api/auth/login")
                .defaultSuccessUrl("/", true)
                .failureUrl("/login.html?error=true")
                .permitAll()
            )
            .rememberMe(rm -> rm
                .key(rememberMeKey)
                .tokenValiditySeconds((int) rememberMeValidity.toSeconds())
                .userDetailsService(inMemoryUserDetailsService)
                .useSecureCookie(false)
            )
            .logout(logout -> logout
                .logoutUrl("/api/auth/logout")
                .logoutSuccessUrl("/")
                .deleteCookies("remember-me")
            );
        return http.build();
    }

    /**
     * /mock/** 完全繞過 Security Filter Chain，避免 Authorization header 被攔截
     */
    @Bean
    public WebSecurityCustomizer webSecurityCustomizer() {
        return web -> web.ignoring().requestMatchers("/mock/**", "/h2-console/**");
    }

    @Bean
    public UserDetailsService inMemoryUserDetailsService(PasswordEncoder passwordEncoder,
                                                         BuiltinUserRepository builtinUserRepository) {
        // 支援 {bcrypt} 或 {noop} 前綴，若無前綴則視為明碼
        String password = adminPassword.startsWith("{") ? adminPassword : "{noop}" + adminPassword;
        var admin = User.builder()
                .username(adminUsername)
                .password(password)
                .roles("ADMIN")
                .build();

        // 測試用 USER 帳號
        var user = User.builder()
                .username("user")
                .password("{bcrypt}$2a$10$P90qs9C/P5LWb/qpUWrVu.rt5qjUNi1VHFtZF4baRU12XJY77u8C6")
                .roles("USER")
                .build();

        InMemoryUserDetailsManager inMemory = new InMemoryUserDetailsManager(admin, user);

        // 組合式 UserDetailsService：BuiltinUserRepository → InMemory admin → LDAP 預設
        return username -> {
            // 1. 先查 BuiltinUserRepository
            Optional<BuiltinUser> builtinUser = builtinUserRepository.findByUsername(username);
            if (builtinUser.isPresent()) {
                BuiltinUser bu = builtinUser.get();
                // 角色去掉 ROLE_ 前綴，Spring Security 的 roles() 會自動加回
                String role = bu.getRole().startsWith("ROLE_")
                        ? bu.getRole().substring(5)
                        : bu.getRole();
                return User.builder()
                        .username(bu.getUsername())
                        .password("{bcrypt}" + bu.getPassword())
                        .roles(role)
                        .disabled(!bu.getEnabled())
                        .build();
            }

            // 2. 再查 InMemory（admin 帳號等）
            try {
                return inMemory.loadUserByUsername(username);
            } catch (UsernameNotFoundException e) {
                // 3. LDAP 用戶：建立預設權限的 UserDetails（remember-me 用）
                return User.builder()
                        .username(username)
                        .password("{noop}")
                        .roles("USER")
                        .build();
            }
        };
    }

    @Bean
    public AuthenticationProvider daoAuthenticationProvider(UserDetailsService userDetailsService, PasswordEncoder passwordEncoder) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider();
        provider.setUserDetailsService(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder);
        return provider;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    /**
     * 自訂 EntryPoint：回傳 401 JSON 但不帶 WWW-Authenticate header，
     * 避免瀏覽器彈出原生 Basic Auth 登入框。
     */
    private AuthenticationEntryPoint noPopupEntryPoint() {
        return (request, response, authException) -> {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"error\":\"Unauthorized\"}");
        };
    }
}

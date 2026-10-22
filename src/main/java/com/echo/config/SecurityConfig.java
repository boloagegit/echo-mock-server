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
     * Open for reading only, you need to log in to modify
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
                // Fully open
                .requestMatchers("/webjars/**", "/login.html", "/*.css", "/*.js", "/composables/*.js", "/favicon.svg", "/favicon.ico", "/i18n/**", "/components/**", "/api/auth/**").permitAll()
                .requestMatchers(HttpMethod.GET,
                        "/actuator/health/liveness", "/actuator/health/readiness").permitAll()
                // Forgot password, self-service registration - public (must be before builtin-users/**)
                .requestMatchers("/api/admin/builtin-users/forgot-password", "/api/admin/builtin-users/register").permitAll()
                // Built-in account management - ADMIN only
                .requestMatchers("/api/admin/builtin-users/**").hasRole("ADMIN")
                .requestMatchers("/api/admin/resources", "/api/admin/resources/**").hasRole("ADMIN")
                // SDK downloads are available to every signed-in user.
                .requestMatchers("/api/admin/sdk", "/api/admin/sdk/**").authenticated()
                // Logged-in users can choose the secure read-only connection DTO; management and testing are still limited to ADMIN
                .requestMatchers(HttpMethod.GET,
                        "/api/admin/jms-target-connections",
                        "/api/admin/http-target-connections").authenticated()
                // The request log may contain the downstream address and request content and is not publicly readable.
                .requestMatchers(HttpMethod.GET,
                        "/api/admin/logs",
                        "/api/admin/logs/summary",
                        "/api/admin/logs/traffic",
                        "/api/admin/logs/*/detail").authenticated()
                .requestMatchers("/api/admin/jms-target-connections/**").hasRole("ADMIN")
                .requestMatchers("/api/admin/http-target-connections/**").hasRole("ADMIN")
                // Issue Report — reply/resolve/reopen/delete only ADMIN
                .requestMatchers("/api/admin/issues/*/reply", "/api/admin/issues/*/resolve", "/api/admin/issues/*/reopen").hasRole("ADMIN")
                .requestMatchers(HttpMethod.DELETE, "/api/admin/issues/*").hasRole("ADMIN")
                // Issue Report — Create/List/View Login required
                .requestMatchers("/api/admin/issues/**").authenticated()
                // User can change password by himself - already logged in
                .requestMatchers("/api/account/change-password").authenticated()
                // Batch operation only ADMIN (must precede permitAll)
                .requestMatchers("/api/admin/rules/export", "/api/admin/rules/import-batch", "/api/admin/rules/import-excel", "/api/admin/rules/batch", "/api/admin/rules/batch/*", "/api/admin/rules/tag/*/*/*", "/api/admin/rules/all", "/api/admin/responses/all", "/api/admin/responses/export", "/api/admin/audit/all", "/api/admin/setup").hasRole("ADMIN")
                // Retrieving all rules/all response contents at one time requires logging in: the amount of data increases with rules and responses, and a large number of calls without logging in may exhaust memory.
                // The screen uses the pagination and summary open below API
                .requestMatchers(HttpMethod.GET, "/api/admin/rules", "/api/admin/responses").authenticated()
                // Read only API open
                .requestMatchers(HttpMethod.GET, "/", "/index.html", "/api/admin/status", "/api/admin/rules/*", "/api/admin/rules/import-template", "/api/admin/rules/*/audit", "/api/admin/rules/*/order", "/api/admin/audit", "/api/admin/responses/*", "/api/admin/responses/*/rules", "/api/admin/responses/summary").permitAll()
                // Others require login (POST/PUT/DELETE)
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
     * /mock/** completely bypasses Security Filter Chain to prevent Authorization header from being intercepted
     */
    @Bean
    public WebSecurityCustomizer webSecurityCustomizer() {
        return web -> web.ignoring().requestMatchers("/mock/**");
    }

    @Bean
    public UserDetailsService inMemoryUserDetailsService(PasswordEncoder passwordEncoder,
                                                         BuiltinUserRepository builtinUserRepository) {
        // Supports {bcrypt} or {noop} prefix, if there is no prefix, it will be regarded as clear code
        String password = adminPassword.startsWith("{") ? adminPassword : "{noop}" + adminPassword;
        var admin = User.builder()
                .username(adminUsername)
                .password(password)
                .roles("ADMIN")
                .build();

        // USER account for testing
        var user = User.builder()
                .username("user")
                .password("{bcrypt}$2a$10$P90qs9C/P5LWb/qpUWrVu.rt5qjUNi1VHFtZF4baRU12XJY77u8C6")
                .roles("USER")
                .build();

        InMemoryUserDetailsManager inMemory = new InMemoryUserDetailsManager(admin, user);

        // Combined UserDetailsService: BuiltinUserRepository → InMemory admin → LDAP Default
        return username -> {
            // 1. Check BuiltinUserRepository first
            Optional<BuiltinUser> builtinUser = builtinUserRepository.findByUsername(username);
            if (builtinUser.isPresent()) {
                BuiltinUser bu = builtinUser.get();
                // The ROLE_ prefix is removed from the character, and the roles() of Spring Security will be automatically added back.
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

            // 2. Check InMemory (admin account number, etc.)
            try {
                return inMemory.loadUserByUsername(username);
            } catch (UsernameNotFoundException e) {
                // 3. LDAP user: Create UserDetails with default permissions (for remember-me)
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
     * Customized EntryPoint: Returns 401 JSON without WWW-Authenticate header,
     * Prevent the browser from popping up the native Basic Auth login box.
     */
    private AuthenticationEntryPoint noPopupEntryPoint() {
        return (request, response, authException) -> {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"error\":\"Unauthorized\"}");
        };
    }
}

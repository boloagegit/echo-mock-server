package com.echo.config;

import com.echo.entity.BuiltinUser;
import com.echo.repository.BuiltinUserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class SecurityConfigTest {

    private SecurityConfig config;
    private PasswordEncoder passwordEncoder;
    private BuiltinUserRepository builtinUserRepository;

    @BeforeEach
    void setUp() {
        config = new SecurityConfig();
        passwordEncoder = config.passwordEncoder();
        builtinUserRepository = mock(BuiltinUserRepository.class);
        ReflectionTestUtils.setField(config, "adminUsername", "admin");
        ReflectionTestUtils.setField(config, "adminPassword", "secret");
        ReflectionTestUtils.setField(config, "rememberMeKey", "test-key");
        ReflectionTestUtils.setField(config, "rememberMeValidity", java.time.Duration.ofDays(180));
    }

    @Test
    void securityFilterChain_shouldConfigureBothProviders() throws Exception {
        HttpSecurity http = mock(HttpSecurity.class, RETURNS_DEEP_STUBS);
        AuthenticationProvider ldapProvider = mock(AuthenticationProvider.class);
        AuthenticationProvider daoProvider = mock(AuthenticationProvider.class);
        when(builtinUserRepository.findByUsername(anyString())).thenReturn(Optional.empty());
        UserDetailsService userDetailsService = config.inMemoryUserDetailsService(passwordEncoder, builtinUserRepository);
        when(http.csrf(any())).thenReturn(http);
        when(http.headers(any())).thenReturn(http);
        when(http.authenticationProvider(any())).thenReturn(http);
        when(http.authorizeHttpRequests(any())).thenReturn(http);
        when(http.exceptionHandling(any())).thenReturn(http);
        when(http.formLogin(any())).thenReturn(http);
        when(http.httpBasic(any())).thenReturn(http);
        when(http.rememberMe(any())).thenReturn(http);
        when(http.logout(any())).thenReturn(http);
        when(http.build()).thenReturn(null);

        config.securityFilterChain(http, ldapProvider, daoProvider, userDetailsService);

        verify(http, times(2)).authenticationProvider(any());
        verify(http).formLogin(any());
        verify(http).httpBasic(any());
    }

    @Test
    void inMemoryUserDetailsService_shouldCreateEncodedAdminUser() {
        when(builtinUserRepository.findByUsername("admin")).thenReturn(Optional.empty());
        UserDetailsService service = config.inMemoryUserDetailsService(passwordEncoder, builtinUserRepository);
        var user = service.loadUserByUsername("admin");
        
        assertThat(user.getUsername()).isEqualTo("admin");
        assertThat(user.getAuthorities()).anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));
        assertThat(user.getPassword()).startsWith("{");
        assertThat(passwordEncoder.matches("secret", user.getPassword())).isTrue();
    }

    @Test
    void passwordEncoder_shouldMatchCorrectAndRejectWrong() {
        String encoded = passwordEncoder.encode("test");
        assertThat(passwordEncoder.matches("test", encoded)).isTrue();
        assertThat(passwordEncoder.matches("wrong", encoded)).isFalse();
    }

    @Test
    void daoAuthenticationProvider_shouldBeConfigured() {
        when(builtinUserRepository.findByUsername(anyString())).thenReturn(Optional.empty());
        UserDetailsService userDetailsService = config.inMemoryUserDetailsService(passwordEncoder, builtinUserRepository);
        var provider = config.daoAuthenticationProvider(userDetailsService, passwordEncoder);
        assertThat(provider).isNotNull();
    }

    @Test
    void inMemoryUserDetailsService_shouldFallbackForLdapUser() {
        when(builtinUserRepository.findByUsername("ldap-user")).thenReturn(Optional.empty());
        UserDetailsService service = config.inMemoryUserDetailsService(passwordEncoder, builtinUserRepository);
        var user = service.loadUserByUsername("ldap-user");
        
        assertThat(user.getUsername()).isEqualTo("ldap-user");
        assertThat(user.getAuthorities()).anyMatch(a -> a.getAuthority().equals("ROLE_USER"));
    }

    @Test
    void webSecurityCustomizer_shouldIgnoreMockPath() {
        var customizer = config.webSecurityCustomizer();
        assertThat(customizer).isNotNull();
        // WebSecurityCustomizer 會讓 /mock/** 完全繞過 Security Filter Chain
    }

    @Test
    void compositeUserDetailsService_shouldReturnBuiltinUserFirst() {
        org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder bcrypt =
                new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder();
        String rawHash = bcrypt.encode("vendor123");
        BuiltinUser builtinUser = BuiltinUser.builder()
                .username("vendor01")
                .password(rawHash)
                .role("ROLE_USER")
                .enabled(true)
                .forceChangePassword(false)
                .build();
        when(builtinUserRepository.findByUsername("vendor01")).thenReturn(Optional.of(builtinUser));

        UserDetailsService service = config.inMemoryUserDetailsService(passwordEncoder, builtinUserRepository);
        var user = service.loadUserByUsername("vendor01");

        assertThat(user.getUsername()).isEqualTo("vendor01");
        assertThat(user.getAuthorities()).anyMatch(a -> a.getAuthority().equals("ROLE_USER"));
        assertThat(user.isEnabled()).isTrue();
        assertThat(passwordEncoder.matches("vendor123", user.getPassword())).isTrue();
    }

    @Test
    void compositeUserDetailsService_shouldReturnDisabledUserDetails() {
        org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder bcrypt =
                new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder();
        BuiltinUser builtinUser = BuiltinUser.builder()
                .username("disabled-user")
                .password(bcrypt.encode("pass123"))
                .role("ROLE_USER")
                .enabled(false)
                .forceChangePassword(false)
                .build();
        when(builtinUserRepository.findByUsername("disabled-user")).thenReturn(Optional.of(builtinUser));

        UserDetailsService service = config.inMemoryUserDetailsService(passwordEncoder, builtinUserRepository);
        var user = service.loadUserByUsername("disabled-user");

        assertThat(user.getUsername()).isEqualTo("disabled-user");
        assertThat(user.isEnabled()).isFalse();
    }

    @Test
    void compositeUserDetailsService_shouldReturnAdminRoleForBuiltinAdmin() {
        org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder bcrypt =
                new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder();
        BuiltinUser builtinUser = BuiltinUser.builder()
                .username("builtin-admin")
                .password(bcrypt.encode("adminpass"))
                .role("ROLE_ADMIN")
                .enabled(true)
                .forceChangePassword(false)
                .build();
        when(builtinUserRepository.findByUsername("builtin-admin")).thenReturn(Optional.of(builtinUser));

        UserDetailsService service = config.inMemoryUserDetailsService(passwordEncoder, builtinUserRepository);
        var user = service.loadUserByUsername("builtin-admin");

        assertThat(user.getUsername()).isEqualTo("builtin-admin");
        assertThat(user.getAuthorities()).anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));
    }

    @Test
    void compositeUserDetailsService_shouldPrioritizeBuiltinOverInMemory() {
        // If a builtin user has the same username as the in-memory admin,
        // the builtin user should take priority
        org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder bcrypt =
                new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder();
        BuiltinUser builtinUser = BuiltinUser.builder()
                .username("admin")
                .password(bcrypt.encode("builtin-admin-pass"))
                .role("ROLE_ADMIN")
                .enabled(true)
                .forceChangePassword(false)
                .build();
        when(builtinUserRepository.findByUsername("admin")).thenReturn(Optional.of(builtinUser));

        UserDetailsService service = config.inMemoryUserDetailsService(passwordEncoder, builtinUserRepository);
        var user = service.loadUserByUsername("admin");

        // Should match the builtin password, not the in-memory "secret"
        assertThat(passwordEncoder.matches("builtin-admin-pass", user.getPassword())).isTrue();
        assertThat(passwordEncoder.matches("secret", user.getPassword())).isFalse();
    }
}

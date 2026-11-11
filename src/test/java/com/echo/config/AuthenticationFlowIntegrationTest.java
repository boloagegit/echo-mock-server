package com.echo.config;

import com.echo.entity.BuiltinUser;
import com.echo.repository.BuiltinUserRepository;
import com.unboundid.ldap.listener.*;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.*;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.junit.jupiter.web.SpringJUnitWebConfig;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import java.util.Optional;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringJUnitWebConfig(AuthenticationFlowIntegrationTest.Config.class)
class AuthenticationFlowIntegrationTest {
    private static final InMemoryDirectoryServer LDAP = directory();
    private static final java.nio.file.Path KEY_FILE = keyFile();
    static java.nio.file.Path keyFile() {
        try { return java.nio.file.Files.createTempDirectory("echo-auth-flow-").resolve("remember-me.key"); }
        catch (java.io.IOException failure) { throw new IllegalStateException(failure); }
    }
    @Autowired WebApplicationContext context;
    @Autowired BuiltinUserRepository users;
    @Autowired PasswordEncoder encoder;
    private MockMvc mvc;

    @org.springframework.boot.test.context.TestConfiguration
    @EnableWebMvc
    @Import({SecurityConfig.class, LdapConfig.class})
    static class Config {
        @Bean static org.springframework.core.convert.ConversionService conversionService() {
            return new org.springframework.boot.convert.ApplicationConversionService();
        }
        @Bean BuiltinUserRepository users() { return mock(BuiltinUserRepository.class); }
        @Bean Endpoints endpoints() { return new Endpoints(); }
    }
    @RestController
    static class Endpoints {
        @GetMapping("/api/admin/logs") String reader() { return "reader"; }
        @GetMapping("/api/admin/builtin-users/check") String admin() { return "admin"; }
        @GetMapping("/mock/probe") String mockEndpoint() { return "mock"; }
    }
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("echo.admin.username", () -> "admin");
        registry.add("echo.admin.password", () -> "local-secret");
        registry.add("echo.remember-me.key", () -> "change-me-in-production");
        registry.add("echo.remember-me.key-file", () -> KEY_FILE.toString());
        registry.add("echo.remember-me.validity", () -> "1d");
        registry.add("echo.ldap.enabled", () -> "true");
        registry.add("echo.ldap.url", () -> "ldap://127.0.0.1:" + LDAP.getListenPort());
        registry.add("echo.ldap.base-dn", () -> "dc=example,dc=com");
        registry.add("echo.ldap.user-pattern", () -> "uid={0},ou=users");
    }
    static InMemoryDirectoryServer directory() {
        try {
            var config = new InMemoryDirectoryServerConfig("dc=example,dc=com");
            config.setListenerConfigs(InMemoryListenerConfig.createLDAPConfig("audit", 0));
            var server = new InMemoryDirectoryServer(config);
            server.add("dn: dc=example,dc=com", "objectClass: domain", "dc: example");
            server.add("dn: ou=users,dc=example,dc=com", "objectClass: organizationalUnit", "ou: users");
            server.add("dn: uid=directory-user,ou=users,dc=example,dc=com", "objectClass: inetOrgPerson",
                    "uid: directory-user", "cn: Directory User", "sn: User", "userPassword: directory-secret");
            server.startListening();
            return server;
        } catch (Exception failure) { throw new IllegalStateException(failure); }
    }
    @AfterAll static void closeDirectory() throws Exception {
        LDAP.shutDown(true);
        java.nio.file.Files.deleteIfExists(KEY_FILE);
        java.nio.file.Files.deleteIfExists(KEY_FILE.getParent());
    }
    @BeforeEach void prepare() {
        reset(users);
        when(users.findByUsername(anyString())).thenReturn(Optional.empty());
        mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup(context)
                .apply(springSecurity()).build();
    }
    @Test void unknownAndIncorrectCredentialsAreRejected() throws Exception {
        for (String name : new String[]{"missing", "directory-user", "admin"}) {
            mvc.perform(get("/api/admin/logs").with(httpBasic(name, ""))).andExpect(status().isUnauthorized());
            mvc.perform(get("/api/admin/logs").with(httpBasic(name, "wrong"))).andExpect(status().isUnauthorized());
        }
    }
    @Test void localAndLdapPasswordsRetainTheirRoles() throws Exception {
        mvc.perform(get("/api/admin/builtin-users/check").with(httpBasic("admin", "local-secret")))
                .andExpect(status().isOk());
        mvc.perform(get("/api/admin/logs").with(httpBasic("directory-user", "directory-secret")))
                .andExpect(status().isOk());
        mvc.perform(get("/api/admin/builtin-users/check").with(httpBasic("directory-user", "directory-secret")))
                .andExpect(status().isForbidden());
    }
    @Test void localAndLdapRememberMeStillWorkInNewSessions() throws Exception {
        for (String[] credentials : new String[][]{{"admin", "local-secret"}, {"directory-user", "directory-secret"}}) {
            Cookie cookie = loginCookie(credentials[0], credentials[1]);
            mvc.perform(get("/api/admin/logs").cookie(cookie)).andExpect(status().isOk());
            mvc.perform(get("/api/admin/logs").cookie(new Cookie("remember-me", cookie.getValue() + "invalid")))
                    .andExpect(status().isUnauthorized());
        }
    }
    @Test void disabledLocalAccountIsRejectedIncludingRememberMe() throws Exception {
        BuiltinUser user = BuiltinUser.builder().username("vendor").password(encoder.encode("vendor-secret").substring(8))
                .role("ROLE_USER").enabled(true).build();
        when(users.findByUsername("vendor")).thenReturn(Optional.of(user));
        Cookie cookie = loginCookie("vendor", "vendor-secret");
        user.setEnabled(false);
        mvc.perform(get("/api/admin/logs").with(httpBasic("vendor", "vendor-secret"))).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/admin/logs").cookie(cookie)).andExpect(status().isUnauthorized());
    }
    @Test void changingLocalPasswordInvalidatesTheOldRememberMeCookie() throws Exception {
        BuiltinUser user = BuiltinUser.builder().username("vendor").password(encoder.encode("vendor-secret").substring(8))
                .role("ROLE_USER").enabled(true).build();
        when(users.findByUsername("vendor")).thenReturn(Optional.of(user));
        Cookie cookie = loginCookie("vendor", "vendor-secret");
        user.setPassword(encoder.encode("changed-secret").substring(8));
        mvc.perform(get("/api/admin/logs").cookie(cookie)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/admin/logs").with(httpBasic("vendor", "changed-secret"))).andExpect(status().isOk());
    }
    @Test void rememberedLdapIdentitySurvivesServiceReconstructionFromTheStoredKey() throws Exception {
        Cookie cookie = loginCookie("directory-user", "directory-secret");
        var service = new org.springframework.security.web.authentication.rememberme.TokenBasedRememberMeServices(
                RememberMeSigningKey.resolve("", KEY_FILE),
                context.getBean("rememberMeUserDetailsService", org.springframework.security.core.userdetails.UserDetailsService.class));
        var request = new org.springframework.mock.web.MockHttpServletRequest();
        request.setCookies(cookie);
        var authentication = service.autoLogin(request, new org.springframework.mock.web.MockHttpServletResponse());
        assertThat(authentication).isNotNull();
        assertThat(authentication.getName()).isEqualTo("directory-user");
    }
    @Test void publiclyKnownKeysCannotAuthenticateSyntheticIdentities() throws Exception {
        for (String oldKey : new String[]{"change-me-in-production", "echo-remember-me-secret"}) {
            String name = "never-authenticated", expiry = Long.toString(System.currentTimeMillis() + 60000);
            String input = name + ":" + expiry + ":{noop}:" + oldKey;
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8)));
            String token = Base64.getEncoder().encodeToString((name + ":" + expiry + ":SHA256:" + hash).getBytes(StandardCharsets.UTF_8));
            mvc.perform(get("/api/admin/logs").cookie(new Cookie("remember-me", token))).andExpect(status().isUnauthorized());
        }
    }
    @Test void mockTrafficRemainsUnauthenticated() throws Exception {
        mvc.perform(get("/mock/probe")).andExpect(status().isOk()).andExpect(content().string("mock"));
    }
    private Cookie loginCookie(String username, String password) throws Exception {
        var result = mvc.perform(post("/api/auth/login").param("username", username).param("password", password)
                        .param("remember-me", "true")).andExpect(status().isFound()).andReturn();
        Cookie cookie = result.getResponse().getCookie("remember-me");
        assertThat(cookie).isNotNull();
        assertThat(cookie.getMaxAge()).isPositive();
        return cookie;
    }
}

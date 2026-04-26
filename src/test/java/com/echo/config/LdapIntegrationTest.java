package com.echo.config;

import com.unboundid.ldap.listener.InMemoryDirectoryServer;
import com.unboundid.ldap.listener.InMemoryDirectoryServerConfig;
import com.unboundid.ldap.listener.InMemoryListenerConfig;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * LDAP 整合測試 - 使用 UnboundID 內嵌 LDAP Server
 */
class LdapIntegrationTest {

    private static InMemoryDirectoryServer ldapServer;
    private static int ldapPort;

    @BeforeAll
    static void startLdapServer() throws Exception {
        InMemoryDirectoryServerConfig config = new InMemoryDirectoryServerConfig("dc=example,dc=com");
        config.addAdditionalBindCredentials("cn=admin,dc=example,dc=com", "admin");
        config.setListenerConfigs(InMemoryListenerConfig.createLDAPConfig("default", 0));
        
        ldapServer = new InMemoryDirectoryServer(config);
        
        // 建立測試用戶
        ldapServer.add("dn: dc=example,dc=com", "objectClass: domain", "dc: example");
        ldapServer.add("dn: ou=users,dc=example,dc=com", "objectClass: organizationalUnit", "ou: users");
        ldapServer.add(
            "dn: uid=testuser,ou=users,dc=example,dc=com",
            "objectClass: inetOrgPerson",
            "uid: testuser",
            "cn: Test User",
            "sn: User",
            "userPassword: testpass"
        );
        ldapServer.add(
            "dn: uid=admin,ou=users,dc=example,dc=com",
            "objectClass: inetOrgPerson",
            "uid: admin",
            "cn: Admin User",
            "sn: Admin",
            "userPassword: adminpass"
        );
        
        ldapServer.startListening();
        ldapPort = ldapServer.getListenPort();
    }

    @AfterAll
    static void stopLdapServer() {
        if (ldapServer != null) {
            ldapServer.shutDown(true);
        }
    }

    @Test
    void shouldAuthenticateValidUser() {
        LdapConfig config = createLdapConfig();
        AuthenticationProvider provider = config.ldapAuthenticationProvider();

        var auth = provider.authenticate(
            new UsernamePasswordAuthenticationToken("testuser", "testpass"));

        assertThat(auth).isNotNull();
        assertThat(auth.isAuthenticated()).isTrue();
        assertThat(auth.getName()).isEqualTo("testuser");
    }

    @Test
    void shouldAuthenticateAdminUser() {
        LdapConfig config = createLdapConfig();
        AuthenticationProvider provider = config.ldapAuthenticationProvider();

        var auth = provider.authenticate(
            new UsernamePasswordAuthenticationToken("admin", "adminpass"));

        assertThat(auth).isNotNull();
        assertThat(auth.isAuthenticated()).isTrue();
    }

    @Test
    void shouldRejectInvalidPassword() {
        LdapConfig config = createLdapConfig();
        AuthenticationProvider provider = config.ldapAuthenticationProvider();

        var auth = provider.authenticate(
            new UsernamePasswordAuthenticationToken("testuser", "wrongpass"));

        assertThat(auth).isNull();
    }

    @Test
    void shouldRejectNonExistentUser() {
        LdapConfig config = createLdapConfig();
        AuthenticationProvider provider = config.ldapAuthenticationProvider();

        var auth = provider.authenticate(
            new UsernamePasswordAuthenticationToken("nouser", "anypass"));

        assertThat(auth).isNull();
    }

    @Test
    void shouldSupportUsernamePasswordToken_whenEnabled() {
        LdapConfig config = createLdapConfig();
        AuthenticationProvider provider = config.ldapAuthenticationProvider();

        assertThat(provider.supports(UsernamePasswordAuthenticationToken.class)).isTrue();
    }

    private LdapConfig createLdapConfig() {
        LdapConfig config = new LdapConfig();
        ReflectionTestUtils.setField(config, "ldapEnabled", true);
        ReflectionTestUtils.setField(config, "ldapUrl", "ldap://localhost:" + ldapPort);
        ReflectionTestUtils.setField(config, "baseDn", "dc=example,dc=com");
        ReflectionTestUtils.setField(config, "userPattern", "uid={0},ou=users");
        return config;
    }
}

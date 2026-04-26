package com.echo.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

class LdapConfigTest {

    @ParameterizedTest
    @CsvSource({
        "false, ldap://localhost:389, dc=example,dc=com",  // disabled
        "true, '', dc=example,dc=com",                      // empty url
        "true, ldap://localhost:389, ''",                   // empty baseDn
    })
    void ldapAuthenticationProvider_shouldReturnNull_whenNotConfigured(boolean enabled, String url, String baseDn) {
        LdapConfig config = new LdapConfig();
        ReflectionTestUtils.setField(config, "ldapEnabled", enabled);
        ReflectionTestUtils.setField(config, "ldapUrl", url);
        ReflectionTestUtils.setField(config, "baseDn", baseDn);
        ReflectionTestUtils.setField(config, "userPattern", "uid={0}");

        AuthenticationProvider provider = config.ldapAuthenticationProvider();
        var result = provider.authenticate(new UsernamePasswordAuthenticationToken("user", "pass"));

        assertThat(result).isNull();
    }

    @Test
    void ldapAuthenticationProvider_shouldReturnNull_whenConnectionFails() {
        LdapConfig config = new LdapConfig();
        ReflectionTestUtils.setField(config, "ldapEnabled", true);
        ReflectionTestUtils.setField(config, "ldapUrl", "ldap://invalid-host:389");
        ReflectionTestUtils.setField(config, "baseDn", "dc=example,dc=com");
        ReflectionTestUtils.setField(config, "userPattern", "uid={0}");

        AuthenticationProvider provider = config.ldapAuthenticationProvider();
        var result = provider.authenticate(new UsernamePasswordAuthenticationToken("user", "pass"));

        assertThat(result).isNull();
    }

    @Test
    void ldapAuthenticationProvider_supports_shouldReturnFalse_whenDisabled() {
        LdapConfig config = new LdapConfig();
        ReflectionTestUtils.setField(config, "ldapEnabled", false);
        ReflectionTestUtils.setField(config, "ldapUrl", "");
        ReflectionTestUtils.setField(config, "baseDn", "");
        ReflectionTestUtils.setField(config, "userPattern", "uid={0}");

        AuthenticationProvider provider = config.ldapAuthenticationProvider();

        assertThat(provider.supports(UsernamePasswordAuthenticationToken.class)).isFalse();
    }

    @Test
    void ldapAuthenticationProvider_supports_shouldReturnTrue_whenEnabled() {
        LdapConfig config = new LdapConfig();
        ReflectionTestUtils.setField(config, "ldapEnabled", true);
        ReflectionTestUtils.setField(config, "ldapUrl", "ldap://localhost:389");
        ReflectionTestUtils.setField(config, "baseDn", "dc=example,dc=com");
        ReflectionTestUtils.setField(config, "userPattern", "uid={0}");

        AuthenticationProvider provider = config.ldapAuthenticationProvider();

        assertThat(provider.supports(UsernamePasswordAuthenticationToken.class)).isTrue();
    }
}

package com.echo.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.ldap.core.support.LdapContextSource;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.ldap.authentication.BindAuthenticator;
import org.springframework.security.ldap.authentication.LdapAuthenticationProvider;

/**
 * LDAP 認證配置
 * <p>
 * 當 echo.ldap.enabled=true 時啟用 LDAP 認證
 */
@Configuration
@Slf4j
public class LdapConfig {

    @Value("${echo.ldap.enabled:false}")
    private boolean ldapEnabled;

    @Value("${echo.ldap.url:}")
    private String ldapUrl;

    @Value("${echo.ldap.base-dn:}")
    private String baseDn;

    @Value("${echo.ldap.user-pattern:uid={0}}")
    private String userPattern;

    @Bean
    public AuthenticationProvider ldapAuthenticationProvider() {
        return new AuthenticationProvider() {
            @Override
            public Authentication authenticate(Authentication authentication) {
                
                if (!ldapEnabled || ldapUrl.isEmpty() || baseDn.isEmpty()) {
                    return null; // 讓其他 provider 處理
                }

                try {
                    LdapContextSource contextSource = new LdapContextSource();
                    contextSource.setUrl(ldapUrl);
                    contextSource.setBase(baseDn);
                    contextSource.afterPropertiesSet();

                    BindAuthenticator authenticator = new BindAuthenticator(contextSource);
                    authenticator.setUserDnPatterns(new String[]{userPattern});
                    authenticator.afterPropertiesSet();

                    LdapAuthenticationProvider provider = new LdapAuthenticationProvider(authenticator);
                    return provider.authenticate(authentication);
                } catch (Exception e) {
                    log.warn("LDAP authentication failed: {}", e.getMessage());
                    return null;
                }
            }

            @Override
            public boolean supports(Class<?> authentication) {
                // LDAP 未啟用時不支援，讓 DaoAuthenticationProvider 處理
                if (!ldapEnabled || ldapUrl.isEmpty() || baseDn.isEmpty()) {
                    return false;
                }
                return UsernamePasswordAuthenticationToken.class.isAssignableFrom(authentication);
            }
        };
    }
}

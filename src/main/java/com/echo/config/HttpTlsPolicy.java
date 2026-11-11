package com.echo.config;

import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ssl.SslBundles;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLException;
import javax.net.ssl.TrustManagerFactory;
import java.security.GeneralSecurityException;

/** Startup-only HTTP trust configuration; never changes JVM or JMS trust settings. */
public final class HttpTlsPolicy {
    private final boolean legacyVerificationEnabled;
    private final TrustManagerFactory trustManagerFactory;

    @Autowired
    public HttpTlsPolicy(
            @Value("${echo.http.tls.legacy-verification-enabled:false}") boolean legacyVerificationEnabled,
            @Value("${echo.http.tls.trust-bundle:}") String trustBundle,
            ObjectProvider<SslBundles> sslBundles) {
        this.legacyVerificationEnabled = legacyVerificationEnabled;
        if (trustBundle == null || trustBundle.isBlank()) {
            this.trustManagerFactory = null;
        } else {
            SslBundles registry = sslBundles.getIfAvailable();
            if (registry == null) {
                throw new IllegalArgumentException("HTTP_TLS_TRUST_BUNDLE_REGISTRY_UNAVAILABLE");
            }
            var bundle = registry.getBundle(trustBundle.trim());
            if (bundle.getStores().getTrustStore() == null) {
                throw new IllegalArgumentException("HTTP_TLS_TRUST_BUNDLE_REQUIRES_TRUST_STORE");
            }
            this.trustManagerFactory = bundle.getManagers().getTrustManagerFactory();
            if (this.trustManagerFactory == null) {
                throw new IllegalArgumentException("HTTP_TLS_TRUST_MANAGER_UNAVAILABLE");
            }
        }
    }

    private HttpTlsPolicy() {
        this.legacyVerificationEnabled = false;
        this.trustManagerFactory = null;
    }

    /** Preserve direct-construction callers and the historical default. */
    public static HttpTlsPolicy compatible() {
        return new HttpTlsPolicy();
    }

    public boolean legacyVerificationEnabled() {
        return legacyVerificationEnabled;
    }

    /** Null retains Reactor Netty's existing system-trust configuration. */
    public SslContext nettyContext(boolean verificationEnabled) throws SSLException {
        if (!verificationEnabled) {
            return SslContextBuilder.forClient().trustManager(InsecureTrustManagerFactory.INSTANCE).build();
        }
        return trustManagerFactory == null ? null
                : SslContextBuilder.forClient().trustManager(trustManagerFactory).build();
    }

    /** Use trust material only: a bundle's client keys are never supplied to HTTP peers. */
    public SSLContext verifiedContext() throws GeneralSecurityException {
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, trustManagerFactory == null ? null : trustManagerFactory.getTrustManagers(), null);
        return context;
    }
}

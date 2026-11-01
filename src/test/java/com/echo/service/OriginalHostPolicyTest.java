package com.echo.service;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OriginalHostPolicyTest {

    @Test
    void defaultsToLegacyWhichAcceptsAnyHostAndFollowsRedirects() {
        OriginalHostPolicy policy = new OriginalHostPolicy("", "");

        assertThat(policy.mode()).isEqualTo(OriginalHostPolicy.Mode.LEGACY);
        assertThat(policy.followRedirects()).isTrue();
        assertThat(policy.rejectionReason("anything.internal:8443/x?y")).isNull();
        assertThat(OriginalHostPolicy.legacy().rejectionReason("10.0.0.1")).isNull();
    }

    @Test
    void disabledRejectsEveryHost() {
        OriginalHostPolicy policy = new OriginalHostPolicy("disabled", "api.example.com");

        assertThat(policy.rejectionReason("api.example.com")).contains("disabled");
        assertThat(policy.followRedirects()).isFalse();
    }

    @Test
    void allowlistMatchesExactHostAndPortWithHttpsDefault() {
        OriginalHostPolicy policy = new OriginalHostPolicy("ALLOWLIST",
                " API.Example.com , internal.svc:8443, 10.0.0.5:443, [::1]:9443 ");

        assertThat(policy.followRedirects()).isFalse();
        assertThat(policy.rejectionReason("api.example.com")).isNull();
        assertThat(policy.rejectionReason("api.example.com:443")).isNull();
        assertThat(policy.rejectionReason("Internal.SVC:8443")).isNull();
        assertThat(policy.rejectionReason("10.0.0.5")).isNull();
        assertThat(policy.rejectionReason("[::1]:9443")).isNull();

        assertThat(policy.rejectionReason("api.example.com:8443")).isNotNull();
        assertThat(policy.rejectionReason("internal.svc")).isNotNull();
        assertThat(policy.rejectionReason("sub.api.example.com")).isNotNull();
        assertThat(policy.rejectionReason("[::1]")).isNotNull();
        assertThat(policy.rejectionReason(null)).isNotNull();
    }

    @Test
    void allowlistRejectsTargetsThatAreNotABareAuthority() {
        OriginalHostPolicy policy = new OriginalHostPolicy(OriginalHostPolicy.Mode.ALLOWLIST,
                Set.of("api.example.com:443"));

        assertThat(policy.rejectionReason("api.example.com@evil.test")).isNotNull();
        assertThat(policy.rejectionReason("evil.test@api.example.com")).isNotNull();
        assertThat(policy.rejectionReason("api.example.com/../x")).isNotNull();
        assertThat(policy.rejectionReason("api.example.com?x")).isNotNull();
        assertThat(policy.rejectionReason("api.example.com#x")).isNotNull();
        assertThat(policy.rejectionReason("https://api.example.com")).isNotNull();
        assertThat(policy.rejectionReason("api.example.com:99999")).isNotNull();
    }

    @Test
    void invalidConfigurationFailsFast() {
        assertThatThrownBy(() -> new OriginalHostPolicy("strict", ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("echo.http.original-host.mode");
        assertThatThrownBy(() -> new OriginalHostPolicy("ALLOWLIST", "https://api.example.com"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("allowed-hosts");
    }

    @Test
    void emptyAllowlistRejectsEverything() {
        OriginalHostPolicy policy = new OriginalHostPolicy("ALLOWLIST", " , ");

        assertThat(policy.rejectionReason("api.example.com")).isNotNull();
    }
}

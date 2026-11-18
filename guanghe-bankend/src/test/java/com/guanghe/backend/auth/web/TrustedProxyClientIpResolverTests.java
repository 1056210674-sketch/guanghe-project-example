package com.guanghe.backend.auth.web;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

class TrustedProxyClientIpResolverTests {

    private final TrustedProxyClientIpResolver resolver = new TrustedProxyClientIpResolver();

    @Test
    void loopbackProxyUsesTheNearestForwardedAddress() {
        MockHttpServletRequest request = requestFrom("127.0.0.1");
        request.addHeader("Forwarded", "for=192.0.2.10;proto=https, for=198.51.100.42");
        request.addHeader("X-Forwarded-For", "203.0.113.99");

        assertThat(resolver.resolve(request)).isEqualTo("198.51.100.42");
    }

    @Test
    void loopbackProxySupportsMultipleXForwardedForValuesAndUsesTheNearestAddress() {
        MockHttpServletRequest request = requestFrom("::1");
        request.addHeader("X-Forwarded-For", "192.0.2.10");
        request.addHeader("X-Forwarded-For", "198.51.100.42");

        assertThat(resolver.resolve(request)).isEqualTo("198.51.100.42");
    }

    @Test
    void bracketedIpv6IsCanonicalizedBeforeItBecomesARateLimitKeyInput() {
        MockHttpServletRequest request = requestFrom("::ffff:127.0.0.1");
        request.addHeader("Forwarded", "for=\"[2001:0DB8:0:0:0:0:0:1]:443\"");

        assertThat(resolver.resolve(request)).isEqualTo("2001:db8:0:0:0:0:0:1");
    }

    @Test
    void directClientCannotForgeItsAddressWithForwardingHeaders() {
        MockHttpServletRequest request = requestFrom("198.51.100.42");
        request.addHeader("Forwarded", "for=203.0.113.9");
        request.addHeader("X-Forwarded-For", "203.0.113.10");

        assertThat(resolver.resolve(request)).isEqualTo("198.51.100.42");
    }

    @Test
    void malformedOrInjectedHeadersFallBackWithoutCreatingAttackerSelectedBuckets() {
        MockHttpServletRequest request = requestFrom("127.0.0.1");
        request.addHeader("Forwarded", "for=203.0.113.9\r\nX-Injected: yes");
        request.addHeader("X-Forwarded-For", "203.0.113.010");

        assertThat(resolver.resolve(request)).isEqualTo("127.0.0.1");
    }

    @Test
    void hostnamesZonesObfuscatedNodesAndOutOfRangePortsAreNotAcceptedAsIps() {
        assertThat(resolveForwarded("for=client.example")).isEqualTo("127.0.0.1");
        assertThat(resolveForwarded("for=\"[fe80::1%25eth0]\"")).isEqualTo("127.0.0.1");
        assertThat(resolveForwarded("for=_private")).isEqualTo("127.0.0.1");
        assertThat(resolveForwarded("for=192.0.2.1:65536")).isEqualTo("127.0.0.1");
    }

    private String resolveForwarded(String value) {
        MockHttpServletRequest request = requestFrom("127.0.0.1");
        request.addHeader("Forwarded", value);
        return resolver.resolve(request);
    }

    private MockHttpServletRequest requestFrom(String remoteAddress) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(remoteAddress);
        return request;
    }
}

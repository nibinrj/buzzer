package dev.nibin.buzzer.gateway.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

import java.net.InetSocketAddress;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Which address the IP key uses, with and without a proxy in front. The integration tests run with no proxy
 * (trusted-proxies=0); the AWS case (1, behind the ALB) is only reachable here.
 */
class RateLimitConfigTest {

    private static final InetSocketAddress TCP_PEER = new InetSocketAddress("10.0.0.5", 51234);

    @Test
    void withoutATrustedProxyTheTcpPeerIsTheClientAndTheHeaderIsIgnored() {
        MockServerWebExchange exchange = exchange("198.51.100.7");

        assertThat(RateLimitConfig.ipKey(RateLimitConfig.clientAddressResolver(0), exchange)).isEqualTo("ip:10.0.0.5");
    }

    @Test
    void behindOneProxyTheLastForwardedEntryIsTheClient() {
        // The ALB appends the address it saw; anything to its left was sent by the client and may be fake.
        MockServerWebExchange exchange = exchange("1.2.3.4, 198.51.100.7");

        assertThat(RateLimitConfig.ipKey(RateLimitConfig.clientAddressResolver(1), exchange))
                .isEqualTo("ip:198.51.100.7");
    }

    private static MockServerWebExchange exchange(String forwardedFor) {
        return MockServerWebExchange.from(MockServerHttpRequest.post("/api/auth/login")
                .remoteAddress(TCP_PEER)
                .header("X-Forwarded-For", forwardedFor));
    }
}

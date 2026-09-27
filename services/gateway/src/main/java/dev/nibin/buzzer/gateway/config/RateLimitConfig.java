package dev.nibin.buzzer.gateway.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.cloud.gateway.support.ipresolver.RemoteAddressResolver;
import org.springframework.cloud.gateway.support.ipresolver.XForwardedRemoteAddressResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;

/**
 * Who a request counts against. The RequestRateLimiter on each route (application.yml) names one of these
 * beans; the key picks the Redis token bucket. The prefixes keep a user id and an IP from ever sharing one.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ClientIpProperties.class)
public class RateLimitConfig {

    /** Identity routes are anonymous (login, register, guest), so the only handle on the caller is its IP. */
    @Bean
    KeyResolver clientIpKeyResolver(ClientIpProperties properties) {
        RemoteAddressResolver clientAddress = clientAddressResolver(properties.trustedProxies());
        return exchange -> Mono.just(ipKey(clientAddress, exchange));
    }

    /**
     * Per user (the verified token's sub) where there is one; per IP on anonymous public paths such as
     * /api/sessions/join. {@code @Primary} because the gateway's filter factory also wants one default resolver.
     */
    @Bean
    @Primary
    KeyResolver userOrClientIpKeyResolver(ClientIpProperties properties) {
        RemoteAddressResolver clientAddress = clientAddressResolver(properties.trustedProxies());
        return exchange -> exchange.getPrincipal()
                .ofType(JwtAuthenticationToken.class)
                .map(authentication -> "user:" + authentication.getName())
                .switchIfEmpty(Mono.fromSupplier(() -> ipKey(clientAddress, exchange)));
    }

    /**
     * With no proxy in front, X-Forwarded-For is whatever the client typed, so it is ignored. With N trusted
     * proxies, the Nth entry from the RIGHT is the one the outermost proxy wrote: a client can prepend fake
     * entries but not change that one.
     */
    static RemoteAddressResolver clientAddressResolver(int trustedProxies) {
        if (trustedProxies == 0) {
            return new RemoteAddressResolver() {
                // The interface's default method: the TCP peer address.
            };
        }
        return XForwardedRemoteAddressResolver.maxTrustedIndex(trustedProxies);
    }

    static String ipKey(RemoteAddressResolver clientAddress, ServerWebExchange exchange) {
        InetSocketAddress address = clientAddress.resolve(exchange);
        if (address == null) {
            return "ip:unknown";
        }
        // Unresolved when taken from X-Forwarded-For: the text as written, no DNS lookup.
        return "ip:" + (address.getAddress() != null ? address.getAddress().getHostAddress() : address.getHostString());
    }
}

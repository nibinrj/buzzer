package dev.nibin.buzzer.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.Assert;

/**
 * gateway.client-ip.*: how many proxies in front of the gateway append to X-Forwarded-For, and are therefore
 * trusted to report the client's address. 0 means "use the TCP peer, ignore the header".
 */
@ConfigurationProperties("gateway.client-ip")
public record ClientIpProperties(int trustedProxies) {

    public ClientIpProperties {
        Assert.isTrue(trustedProxies >= 0, "gateway.client-ip.trusted-proxies must be 0 or more");
    }
}

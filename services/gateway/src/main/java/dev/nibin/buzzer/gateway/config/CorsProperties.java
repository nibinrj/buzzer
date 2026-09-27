package dev.nibin.buzzer.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * gateway.cors.*: browser origins allowed to call the API, as Spring origin patterns
 * ("http://localhost:[*]" = localhost on any port). Empty means no cross-origin browser access.
 */
@ConfigurationProperties("gateway.cors")
public record CorsProperties(List<String> allowedOriginPatterns) {

    public CorsProperties {
        allowedOriginPatterns = allowedOriginPatterns == null ? List.of() : List.copyOf(allowedOriginPatterns);
    }
}

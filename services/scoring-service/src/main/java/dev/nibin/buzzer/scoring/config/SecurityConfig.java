package dev.nibin.buzzer.scoring.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;

/**
 * scoring-service is an OAuth2 resource server, set up exactly like quiz- and session-service: every request carries
 * an RS256 access token from identity-service, verified here with identity's public key (fetched from its JWKS
 * endpoint). The gateway checked the token too, but this service never relies on that (ADR-002).
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(JwtVerificationProperties.class)
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .authorizeHttpRequests(auth -> auth
                        // This chain also guards the management port, and Prometheus sends no token. Actuator is served
                        // ONLY there (management.server.port): on the service port these paths are 404s.
                        .requestMatchers("/actuator/health", "/actuator/info", "/actuator/prometheus").permitAll()
                        // Spring Boot forwards unhandled exceptions to /error. Without this, that forward
                        // is itself denied and a real 500 would reach the client as 401/403.
                        .requestMatchers("/error").permitAll()
                        // Any host or player may read any session's results: scoring knows membership ids, not
                        // user ids, so it can't check who played (see GetResults).
                        .requestMatchers(HttpMethod.GET, "/api/results/sessions/*/leaderboard",
                                "/api/results/sessions/*/final").hasAnyRole("HOST", "PLAYER")
                        // Everything not listed is refused: new endpoints must be opened on purpose.
                        .anyRequest().denyAll())
                .oauth2ResourceServer(resourceServer -> resourceServer
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter())))
                // Stateless bearer tokens: no HTTP session, and no cookies, so CSRF protection has nothing to protect.
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(AbstractHttpConfigurer::disable);
        return http.build();
    }

    /**
     * Verifies signature (RS256 only, the builder's default), expiry, and issuer. Keys are fetched from
     * the JWKS URI on first use and cached; a token with an unknown kid triggers a refetch.
     */
    @Bean
    JwtDecoder jwtDecoder(JwtVerificationProperties properties) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(properties.jwkSetUri()).build();
        decoder.setJwtValidator(jwtValidator(properties.issuer()));
        return decoder;
    }

    /** Timestamps (exp, nbf; 60 s clock skew allowed) plus "iss must be ours". */
    static OAuth2TokenValidator<Jwt> jwtValidator(String issuer) {
        return JwtValidators.createDefaultWithIssuer(issuer);
    }

    /** The principal's name is the JWT sub (the user id); authorities come from the roles claim. */
    static JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(rolesToAuthorities());
        return converter;
    }

    /**
     * identity-service puts {@code "roles": ["HOST"]} in its tokens; Spring's hasRole("HOST") checks for the
     * authority ROLE_HOST. The default converter would read "scope"/"scp" and prefix SCOPE_ instead.
     * Public so tests authenticate through exactly the same mapping.
     */
    public static JwtGrantedAuthoritiesConverter rolesToAuthorities() {
        JwtGrantedAuthoritiesConverter converter = new JwtGrantedAuthoritiesConverter();
        converter.setAuthoritiesClaimName("roles");
        converter.setAuthorityPrefix("ROLE_");
        return converter;
    }
}

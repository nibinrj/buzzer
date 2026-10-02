package dev.nibin.buzzer.gateway.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.oauth2.server.resource.web.server.authentication.ServerBearerTokenAuthenticationConverter;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.ServerAuthenticationEntryPoint;
import org.springframework.security.web.server.authentication.ServerAuthenticationConverter;
import org.springframework.security.web.server.context.NoOpServerSecurityContextRepository;
import org.springframework.security.web.server.firewall.ServerExchangeRejectedHandler;
import org.springframework.security.web.server.savedrequest.NoOpServerRequestCache;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatcher;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatchers;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsConfigurationSource;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;

/**
 * Edge authentication. The gateway is a reactive OAuth2 resource server: RS256 access tokens from
 * identity-service, verified with identity's public keys (JWKS). It only decides "valid token or not";
 * roles and ownership are checked by each service, which also verifies the token itself.
 *
 * <p>Errors raised here (401, firewall 400) are turned into exceptions, so ProblemDetailExceptionHandler writes
 * every gateway error in the same application/problem+json format.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({JwtVerificationProperties.class, CorsProperties.class})
public class SecurityConfig {

    /**
     * Reachable without a token. Login/register/refresh/guest, the JWKS itself, joining a session, and the
     * WebSocket handshake: a browser can't add an Authorization header to it, so session-service authenticates the
     * connection itself, on the STOMP CONNECT frame that follows. Only the exact endpoint, not /ws/**.
     */
    static final String[] PUBLIC_PATHS = {"/api/auth/**", "/.well-known/**", "/api/sessions/join", "/ws"};

    @Bean
    SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http) {
        ServerAuthenticationEntryPoint unauthorized = problemDetailEntryPoint();
        http
                // CORS runs inside the security chain, before authorization: a browser's preflight (OPTIONS,
                // never carrying a token) is answered here instead of being refused with 401.
                .cors(Customizer.withDefaults())
                .authorizeExchange(exchange -> exchange
                        // This chain also guards the management port, and Prometheus sends no token. Actuator is
                        // served ONLY there (management.server.port): on the public port these paths are 404s.
                        // /actuator/health/** = the liveness/readiness groups Kubernetes probes, also tokenless.
                        .pathMatchers("/actuator/health", "/actuator/health/**", "/actuator/info",
                                "/actuator/prometheus").permitAll()
                        // The same two groups on the public port (probes.add-additional-paths). Status only, and the
                        // cluster edge (K.4) forwards only the API paths, so they aren't reachable from outside.
                        .pathMatchers("/livez", "/readyz").permitAll()
                        .pathMatchers(PUBLIC_PATHS).permitAll()
                        // Everything else, routed or not, needs a valid token. Unrouted paths then 404.
                        .anyExchange().authenticated())
                .oauth2ResourceServer(resourceServer -> resourceServer
                        .bearerTokenConverter(bearerTokenOutsidePublicPaths())
                        .authenticationEntryPoint(unauthorized)
                        .jwt(Customizer.withDefaults()))
                // No token at all: the same entry point as a bad token.
                .exceptionHandling(exceptions -> exceptions.authenticationEntryPoint(unauthorized))
                // Stateless: no WebSession to store the SecurityContext in, and no saved request to replay
                // after a login page (there is none). No cookies, so CSRF protection has nothing to protect.
                .securityContextRepository(NoOpServerSecurityContextRepository.getInstance())
                .requestCache(cache -> cache.requestCache(NoOpServerRequestCache.getInstance()))
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .logout(ServerHttpSecurity.LogoutSpec::disable);
        return http.build();
    }

    /**
     * Verifies signature (RS256 only, the builder's default), expiry, and issuer. Keys are fetched from the
     * JWKS URI on first use and cached; a token with an unknown kid triggers a refetch.
     */
    @Bean
    ReactiveJwtDecoder jwtDecoder(JwtVerificationProperties properties) {
        NimbusReactiveJwtDecoder decoder = NimbusReactiveJwtDecoder.withJwkSetUri(properties.jwkSetUri()).build();
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(properties.issuer()));
        return decoder;
    }

    /**
     * Browsers only (curl and services ignore CORS). Bearer tokens travel in a header, not a cookie, so no
     * credentials mode is needed; the Authorization header just has to be allowed.
     */
    @Bean
    CorsConfigurationSource corsConfigurationSource(CorsProperties properties) {
        CorsConfiguration cors = new CorsConfiguration();
        cors.setAllowedOriginPatterns(properties.allowedOriginPatterns());
        cors.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE"));
        cors.setAllowedHeaders(List.of(HttpHeaders.AUTHORIZATION, HttpHeaders.CONTENT_TYPE));
        // Browsers cache the preflight answer this long, instead of sending OPTIONS before every call.
        cors.setMaxAge(Duration.ofHours(1));
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", cors);
        return source;
    }

    /**
     * Spring Security's firewall refuses non-normalized paths ("../", "..;", encoded slashes). Its default
     * handler writes an empty 400; raising it instead gives the same ProblemDetail body as everything else.
     */
    @Bean
    ServerExchangeRejectedHandler problemDetailOnRejectedRequest() {
        return (exchange, rejected) ->
                Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "The request path is not allowed"));
    }

    /**
     * Reads the bearer token everywhere except public paths. By default Spring checks any token it sees, even on
     * a permitAll path, and answers 401 if it is expired, so a client still sending its old access token to
     * /api/auth/refresh would be locked out by the gateway. On public paths the token is left alone: not
     * checked, no X-User-* set, but still forwarded unchanged for the service to use if it wants.
     */
    static ServerAuthenticationConverter bearerTokenOutsidePublicPaths() {
        ServerBearerTokenAuthenticationConverter bearerToken = new ServerBearerTokenAuthenticationConverter();
        ServerWebExchangeMatcher publicPaths = ServerWebExchangeMatchers.pathMatchers(PUBLIC_PATHS);
        return exchange -> publicPaths.matches(exchange)
                .flatMap(match -> match.isMatch() ? Mono.empty() : bearerToken.convert(exchange));
    }

    /**
     * 401 with the RFC 6750 challenge header, body left to the error handler. The reason a token was rejected
     * (expired, wrong issuer, bad signature) is deliberately not echoed back: "invalid_token" is enough.
     */
    static ServerAuthenticationEntryPoint problemDetailEntryPoint() {
        return (exchange, failure) -> {
            String challenge = failure instanceof OAuth2AuthenticationException ? "Bearer error=\"invalid_token\"" : "Bearer";
            exchange.getResponse().getHeaders().set(HttpHeaders.WWW_AUTHENTICATE, challenge);
            return Mono.error(new ResponseStatusException(HttpStatus.UNAUTHORIZED, "A valid bearer token is required"));
        };
    }
}

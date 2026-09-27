package dev.nibin.buzzer.gateway.api;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * Tells services who the caller is, in plain headers they can log. Any X-User-* the client sent is removed
 * first, on every route, so a client can never choose its own identity (header spoofing). The values then
 * come only from a token Spring Security has already verified. On public paths there is no verified token,
 * so the headers are simply absent.
 *
 * <p>Services must not authorize with these headers: they verify the forwarded Authorization token themselves.
 */
@Component
public class UserIdentityHeadersFilter implements GlobalFilter, Ordered {

    public static final String USER_ID = "X-User-Id";
    public static final String USER_ROLES = "X-User-Roles";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        return exchange.getPrincipal()
                .ofType(JwtAuthenticationToken.class)
                .map(authentication -> withIdentityHeaders(exchange, authentication.getToken()))
                .switchIfEmpty(Mono.fromSupplier(() -> withIdentityHeaders(exchange, null)))
                .flatMap(chain::filter);
    }

    /** Runs early, well before the filters that forward the request (they sit at the lowest precedence). */
    @Override
    public int getOrder() {
        return 0;
    }

    /** Strip, then set from the verified token (if any). Header names are case-insensitive. */
    private static ServerWebExchange withIdentityHeaders(ServerWebExchange exchange, Jwt verifiedToken) {
        return exchange.mutate()
                .request(request -> request.headers(headers -> {
                    headers.remove(USER_ID);
                    headers.remove(USER_ROLES);
                    if (verifiedToken != null) {
                        setFrom(verifiedToken, headers);
                    }
                }))
                .build();
    }

    /** sub is the user (or guest) id; roles is identity-service's list claim, e.g. ["HOST", "PLAYER"]. */
    private static void setFrom(Jwt token, HttpHeaders headers) {
        headers.set(USER_ID, token.getSubject());
        List<String> roles = token.getClaimAsStringList("roles");
        if (roles != null && !roles.isEmpty()) {
            headers.set(USER_ROLES, String.join(",", roles));
        }
    }
}

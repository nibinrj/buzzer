package dev.nibin.buzzer.gateway.api;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;
import reactor.core.publisher.Mono;

/**
 * Service-to-service endpoints (/internal/**) are never reachable from outside, token or not. No route
 * points at them anyway; this filter makes the answer a 404 instead of "401 unless you have a token",
 * and keeps it that way even if someone later adds a route that would match.
 *
 * <p>A WebFilter, not a GatewayFilter: it runs before Spring Security's filter chain (order -100) and before
 * route matching, so nothing downstream ever sees the request.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class InternalPathBlockingFilter implements WebFilter {

    /** Also matches "/internal" itself: /** matches zero or more segments. */
    private static final PathPattern INTERNAL = PathPatternParser.defaultInstance.parse("/internal/**");

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        if (INTERNAL.matches(exchange.getRequest().getPath().pathWithinApplication())) {
            // Same 404 as an unrouted path, written by ProblemDetailExceptionHandler.
            return Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND));
        }
        return chain.filter(exchange);
    }
}

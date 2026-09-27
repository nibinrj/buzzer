package dev.nibin.buzzer.gateway.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.codec.HttpMessageWriter;
import org.springframework.http.codec.ServerCodecConfigurer;
import org.springframework.stereotype.Component;
import org.springframework.web.ErrorResponse;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.reactive.function.server.ServerResponse;
import org.springframework.web.reactive.result.view.ViewResolver;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebExceptionHandler;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * Every error the gateway itself produces, as application/problem+json: 401 (SecurityConfig), 400 (firewall),
 * 404 (no route, /internal/**), 429 (rate limiter), 503/504 (gateway routing), and anything unexpected as a
 * bare 500. Responses from the services pass through untouched: they already speak ProblemDetail.
 *
 * <p>A WebExceptionHandler, the reactive equivalent of a servlet's @RestControllerAdvice for errors outside any
 * controller. Order -2 runs it before Boot's DefaultErrorWebExceptionHandler (-1), whose JSON is a different shape.
 */
@Component
@Order(-2)
public class ProblemDetailExceptionHandler implements WebExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ProblemDetailExceptionHandler.class);

    private final ServerResponse.Context responseContext;

    /** The same codecs (Jackson 3 included) WebFlux uses for controller return values. */
    public ProblemDetailExceptionHandler(ServerCodecConfigurer codecs) {
        List<HttpMessageWriter<?>> writers = codecs.getWriters();
        this.responseContext = new ServerResponse.Context() {
            @Override
            public List<HttpMessageWriter<?>> messageWriters() {
                return writers;
            }

            @Override
            public List<ViewResolver> viewResolvers() {
                return List.of();
            }
        };
    }

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, Throwable error) {
        if (exchange.getResponse().isCommitted()) {
            // Headers already sent (e.g. a service failed mid-stream): too late to change the status.
            return Mono.error(error);
        }
        ProblemDetail problem = toProblem(exchange, error);
        return ServerResponse.status(problem.getStatus())
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .bodyValue(problem)
                .flatMap(response -> response.writeTo(exchange, responseContext));
    }

    private static ProblemDetail toProblem(ServerWebExchange exchange, Throwable error) {
        // ResponseStatusException and its gateway subclasses carry a ready-made ProblemDetail.
        if (error instanceof ErrorResponse errorResponse) {
            return errorResponse.getBody();
        }
        // RequestRateLimiter with throw-on-limit raises this (a client-side exception type, reused by the gateway).
        if (error instanceof HttpClientErrorException.TooManyRequests) {
            return ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS,
                    "Too many requests. Slow down and retry shortly.");
        }
        // Unexpected, including a service that is down or dropped the connection. Details go to the log only.
        log.error("Unhandled gateway error on {} {}", exchange.getRequest().getMethod(),
                exchange.getRequest().getPath().value(), error);
        return ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, "Unexpected error.");
    }
}

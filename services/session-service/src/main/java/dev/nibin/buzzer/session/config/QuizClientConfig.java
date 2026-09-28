package dev.nibin.buzzer.session.config;

import dev.nibin.buzzer.session.infrastructure.quiz.HttpQuizCatalog;
import dev.nibin.buzzer.session.infrastructure.quiz.QuizServiceApi;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.circuitbreaker.resilience4j.Resilience4JCircuitBreakerFactory;
import org.springframework.cloud.client.circuitbreaker.Customizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * The HTTP client for quiz-service, and the circuit breaker around it. Spring Cloud's Resilience4j
 * auto-configuration builds the CircuitBreakerFactory from the beans and Customizer defined here.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(QuizClientProperties.class)
public class QuizClientConfig {

    /**
     * An implementation of the QuizServiceApi interface, generated at startup: each call becomes an HTTP request
     * through this RestClient. The builder comes from Boot (Jackson 3 converters, observability hooks).
     */
    @Bean
    QuizServiceApi quizServiceApi(RestClient.Builder builder, QuizClientProperties properties) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                // Plain HTTP inside the VPC. HTTP/1.1 skips the JDK client's h2c upgrade attempt.
                .version(HttpClient.Version.HTTP_1_1)
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.readTimeout());

        RestClient restClient = builder
                .baseUrl(properties.baseUrl())
                .requestFactory(requestFactory)
                .build();
        return HttpServiceProxyFactory.builderFor(RestClientAdapter.create(restClient))
                .build()
                .createClient(QuizServiceApi.class);
    }

    /**
     * Where Resilience4j keeps its breakers (one per name, with its state and call history). Spring Cloud's
     * factory requires this bean; Resilience4j's Boot 3 auto-configuration would normally provide it, but the
     * pom excludes that module.
     */
    @Bean
    CircuitBreakerRegistry circuitBreakerRegistry() {
        return CircuitBreakerRegistry.ofDefaults();
    }

    /** Also required by Spring Cloud's factory, though unused: application.yml disables the TimeLimiter. */
    @Bean
    TimeLimiterRegistry timeLimiterRegistry() {
        return TimeLimiterRegistry.ofDefaults();
    }

    /**
     * Breaker for quiz-service. Judged on the last 10 calls, once at least 5 were made: 50% failures open it.
     * While open (10 s) calls fail immediately without touching the network; then 2 trial calls decide whether
     * it closes again. Configured in Java: Resilience4j's own resilience4j.* properties need its Boot 3
     * auto-configuration, which the pom excludes.
     */
    @Bean
    Customizer<Resilience4JCircuitBreakerFactory> quizServiceCircuitBreaker() {
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(10)
                .minimumNumberOfCalls(5)
                .failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofSeconds(10))
                .permittedNumberOfCallsInHalfOpenState(2)
                .build();
        return factory -> factory.configure(builder -> builder.circuitBreakerConfig(config),
                HttpQuizCatalog.CIRCUIT_BREAKER_ID);
    }
}

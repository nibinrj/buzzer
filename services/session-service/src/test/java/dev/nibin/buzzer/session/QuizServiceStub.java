package dev.nibin.buzzer.session;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;

import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;

/**
 * A fake quiz-service: a real HTTP server (WireMock) on a random free port, started with the test context.
 * Tests program its answers with stubFor(...) and check what it received with verify(...).
 * <p>
 * It also stands in for identity-service's JWKS endpoint (TestTokens.JWKS_PATH), so WebSocket tests can use real
 * signed tokens. Tests that call resetAll() remove that stub too; WebSocket tests stub it again before connecting.
 */
@TestConfiguration(proxyBeanMethods = false)
public class QuizServiceStub {

    @Bean(destroyMethod = "stop")
    WireMockServer quizServiceStub() {
        WireMockServer server = new WireMockServer(options().dynamicPort());
        server.start();
        return server;
    }

    /**
     * Points the quiz client (and the JWT decoder's key lookup) at the stub. A DynamicPropertyRegistrar bean runs
     * before the other beans are created, so the properties records already see these values. The short read
     * timeout keeps the "quiz-service hangs" test fast.
     */
    @Bean
    DynamicPropertyRegistrar quizServiceStubProperties(WireMockServer quizServiceStub) {
        return registry -> {
            registry.add("session.quiz-client.base-url", quizServiceStub::baseUrl);
            registry.add("session.quiz-client.read-timeout", () -> "500ms");
            registry.add("session.security.jwt.jwk-set-uri", () -> quizServiceStub.baseUrl() + TestTokens.JWKS_PATH);
        };
    }
}

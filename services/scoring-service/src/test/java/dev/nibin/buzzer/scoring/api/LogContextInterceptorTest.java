package dev.nibin.buzzer.scoring.api;

import dev.nibin.buzzer.scoring.application.LogContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.HandlerMapping;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** What an HTTP request puts into MDC while it is handled, and that it all goes again afterwards. */
class LogContextInterceptorTest {

    private static final UUID SESSION = UUID.randomUUID();
    private static final UUID USER = UUID.randomUUID();

    private final LogContextInterceptor interceptor = new LogContextInterceptor();
    private final MockHttpServletResponse response = new MockHttpServletResponse();

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void aRequestForASessionIsLabelledWithTheSessionFromItsPathAndTheUser() {
        interceptor.preHandle(request(Map.of("sessionId", SESSION.toString()), USER), response, new Object());

        assertThat(MDC.get(LogContext.SESSION_ID)).isEqualTo(SESSION.toString());
        assertThat(MDC.get(LogContext.USER_ID)).isEqualTo(USER.toString());
    }

    @Test
    void aPathWithoutASessionIdOrWithAMalformedOneCarriesNone() {
        interceptor.preHandle(request(Map.of(), USER), response, new Object());
        assertThat(MDC.get(LogContext.SESSION_ID)).isNull();

        interceptor.preHandle(request(Map.of("sessionId", "not-a-uuid"), USER), response, new Object());
        assertThat(MDC.get(LogContext.SESSION_ID)).isNull();
    }

    @Test
    void anAnonymousRequestCarriesNoUser() {
        interceptor.preHandle(request(Map.of(), null), response, new Object());

        assertThat(MDC.get(LogContext.USER_ID)).isNull();
    }

    @Test
    void afterCompletionBothAreRemovedEvenAfterAnExceptionAndTracingsEntriesStay() {
        MDC.put("traceId", "a-trace");
        MockHttpServletRequest request = request(Map.of("sessionId", SESSION.toString()), USER);
        interceptor.preHandle(request, response, new Object());

        interceptor.afterCompletion(request, response, new Object(), new IllegalStateException("controller failed"));

        assertThat(MDC.get(LogContext.SESSION_ID)).isNull();
        assertThat(MDC.get(LogContext.USER_ID)).isNull();
        assertThat(MDC.get("traceId")).isEqualTo("a-trace");
    }

    /** What Spring MVC has set by the time interceptors run: the matched path variables and the principal. */
    private static MockHttpServletRequest request(Map<String, String> pathVariables, UUID user) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE, pathVariables);
        if (user != null) {
            request.setUserPrincipal(user::toString);
        }
        return request;
    }
}

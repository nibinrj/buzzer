package dev.nibin.buzzer.scoring.api;

import dev.nibin.buzzer.scoring.application.LogContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

import java.security.Principal;
import java.util.Map;

/**
 * Labels every log line of an HTTP request with the {sessionId} from its path and the calling user.
 * <p>
 * An interceptor rather than a servlet filter: by preHandle, Spring MVC has matched the request to a controller
 * method and extracted the path variables, so {sessionId} is read by name instead of parsing the URL again.
 * afterCompletion runs after ApiExceptionHandler has turned an exception into a response, so its lines are labelled
 * too, and it runs even when the controller threw.
 */
@Component
public class LogContextInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE) instanceof Map<?, ?> variables
                && variables.get("sessionId") instanceof String sessionId) {
            LogContext.putIfUuid(LogContext.SESSION_ID, sessionId);
        }
        // Spring Security's JWT authentication; its name is the token's sub.
        Principal user = request.getUserPrincipal();
        if (user != null) {
            LogContext.putIfUuid(LogContext.USER_ID, user.getName());
        }
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler,
            Exception ex) {
        MDC.remove(LogContext.SESSION_ID);
        MDC.remove(LogContext.USER_ID);
    }
}

package dev.nibin.buzzer.session.config;

import jakarta.validation.constraints.NotEmpty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.List;

/** session.websocket.*: which browser origins may open the /ws connection. */
@Validated
@ConfigurationProperties("session.websocket")
public record WebSocketProperties(@NotEmpty List<String> allowedOriginPatterns) {
}

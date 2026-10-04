package dev.nibin.buzzer.session.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The OpenAPI document's title, and the bearer-token scheme behind Swagger UI's "Authorize" button. This document
 * covers the REST side only; the game itself runs over STOMP on /ws, described in docs/asyncapi.yaml.
 */
@Configuration(proxyBeanMethods = false)
class OpenApiConfig {

    @Bean
    OpenAPI openApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("session-service API")
                        .version("v1")
                        .description("Live game sessions: a host creates a session from a published quiz, players join "
                                + "with the room code (guest token), and anyone in the session can reload its state "
                                + "after a reconnect. Questions, answers and reveals travel over STOMP (/ws)."))
                .components(new Components().addSecuritySchemes("bearer", new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")))
                .addSecurityItem(new SecurityRequirement().addList("bearer"));
    }
}
